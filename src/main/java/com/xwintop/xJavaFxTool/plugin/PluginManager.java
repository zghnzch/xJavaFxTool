package com.xwintop.xJavaFxTool.plugin;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.xwintop.xJavaFxTool.model.PluginJarInfo;
import com.xwintop.xJavaFxTool.utils.Config;
import com.xwintop.xJavaFxTool.utils.Config.Keys;
import com.xwintop.xJavaFxTool.utils.XJavaFxSystemUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request.Builder;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

@Slf4j
public class PluginManager {

    public static final String SERVER_PLUGINS_URL = "https://xwintop.gitee.io/maven/plugin-libs/plugin-list.json";

    public static final String LOCAL_PLUGINS_PATH = "./system_plugin_list.json";

    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private static PluginManager instance;

    public static PluginManager getInstance() {
        if (instance == null) {
            instance = new PluginManager(LOCAL_PLUGINS_PATH);
        }
        return instance;
    }

    //////////////////////////////////////////////////////////////

    private final String localPluginsPath;

    private final OkHttpClient pluginDownloader =
        new OkHttpClient.Builder().addInterceptor(new DownloadProgressInterceptor()).build();

    private final List<PluginJarInfo> pluginList = new ArrayList<>(); // 插件列表

    public PluginManager(String localPluginsPath) {
        this.localPluginsPath = localPluginsPath;
        this.loadLocalPluginConfiguration();
    }

    ////////////////////////////////////////////////////////////// 查询插件

    public List<PluginJarInfo> getPluginList() {
        return Collections.unmodifiableList(this.pluginList);
    }


    public PluginJarInfo getPlugin(String jarName) {
        return this.pluginList.stream()
            .filter(plugin -> Objects.equals(plugin.getJarName(), jarName))
            .findFirst().orElse(null);
    }

    public PluginJarInfo getPluginByFxmlPath(String fxmlPath) {
        return this.pluginList.stream()
            .filter(plugin -> Objects.equals(plugin.getFxmlPath(), fxmlPath))
            .findFirst().orElse(null);
    }

    ////////////////////////////////////////////////////////////// 插件列表

    /**
     * 从配置文件中加载本地插件信息
     */
    private void loadLocalPluginConfiguration() {
        try {
            Path path = Paths.get(this.localPluginsPath);
            if (!Files.exists(path)) {
                return;
            }

            String json = new String(Files.readAllBytes(path), DEFAULT_CHARSET);
            JSON.parseArray(json, PluginJarInfo.class).forEach(plugin -> {
                this.addOrUpdatePlugin(plugin, exist -> {
                    exist.setLocalVersionNumber(plugin.getLocalVersionNumber());
                    exist.setIsDownload(plugin.getIsDownload());
                    exist.setIsEnable(plugin.getIsEnable());
                });
            });
        } catch (IOException e) {
            log.error("读取插件配置失败", e);
        }
    }

    /**
     * 解析本地插件文件
     */
    public void loadLocalPlugins() {
        this.pluginList.forEach(plugin -> {
            if (plugin.getIsDownload() != null && plugin.getIsDownload()) {
                try {
                    PluginParser.parse(plugin.getFile(), plugin);
                } catch (Exception e) {
                    log.error("解析失败", e);
                }
            }
        });
    }

    /**
     * 添加本地插件。如果与已有插件同名，则替换已有插件信息
     *
     * @param jarFile 插件文件
     */
    public AddPluginResult addPluginJar(File jarFile) {
        PluginClassLoader tmpClassLoader = new PluginClassLoader(jarFile);
        PluginJarInfo newJarInfo = new PluginJarInfo();
        newJarInfo.setLocalPath(jarFile.getAbsolutePath());
        newJarInfo.setIsEnable(true);
        newJarInfo.setIsDownload(true);
        PluginParser.parse(jarFile, newJarInfo, tmpClassLoader);

        for (int i = 0; i < pluginList.size(); i++) {
            PluginJarInfo jarInfo = pluginList.get(i);
            if (Objects.equals(jarInfo.getFxmlPath(), newJarInfo.getFxmlPath())) {
                newJarInfo.setVersion(jarInfo.getVersion());
                newJarInfo.setSynopsis(jarInfo.getSynopsis());
                pluginList.set(i, newJarInfo);
                saveToFileQuietly();
                return new AddPluginResult(newJarInfo, false);
            }
        }

        pluginList.add(newJarInfo);
        saveToFileQuietly();
        return new AddPluginResult(newJarInfo, true);
    }

    public void loadServerPlugins() {
        try {
            String json = HttpUtil.get(SERVER_PLUGINS_URL);
            JSON.parseArray(json, PluginJarInfo.class).forEach(plugin -> {
                this.addOrUpdatePlugin(plugin, exist -> {
                    exist.setName(plugin.getName());
                    exist.setSynopsis(plugin.getSynopsis());
                    exist.setVersion(plugin.getVersion());
                    exist.setVersionNumber(plugin.getVersionNumber());
                    exist.setDownloadUrl(plugin.getDownloadUrl());
                });
            });
        } catch (Exception e) {
            log.error("下载插件列表失败", e);
        }
    }

    // 异步下载服务器插件列表，便于界面上展示 loading 动画
    public CompletableFuture<Void> loadServerPluginsAsync() {
        return CompletableFuture.runAsync(this::loadServerPlugins);
    }

    /**
     * 向插件列表添加插件信息或更改插件列表中已有的插件信息
     *
     * @param pluginJarInfo 需要添加的插件信息
     * @param ifExists      如果插件已存在，则不会将 pluginJarInfo 加入，
     *                      而是提供已有的插件信息对象供调用者更新其属性
     */
    private void addOrUpdatePlugin(PluginJarInfo pluginJarInfo, Consumer<PluginJarInfo> ifExists) {
        PluginJarInfo exists = getPlugin(pluginJarInfo.getJarName());
        if (exists == null) {
            this.pluginList.add(pluginJarInfo);
        } else {
            ifExists.accept(exists);
        }
    }

    ////////////////////////////////////////////////////////////// 下载插件

    public File downloadPlugin(
        PluginJarInfo pluginJarInfo, BiConsumer<Long, Long> onProgressUpdate
    ) throws IOException {

        PluginJarInfo plugin = getPlugin(pluginJarInfo.getJarName());
        if (plugin == null) {
            throw new IllegalStateException("没有找到插件 " + pluginJarInfo.getJarName());
        }

        File file = pluginJarInfo.getFile();
        FileUtils.forceMkdirParent(file);

        this.currentProgressListener =
            (bytesRead, contentLength, done) -> onProgressUpdate.accept(contentLength, bytesRead);

        try (
            Response response = pluginDownloader
                .newCall(new Builder().url(pluginJarInfo.getDownloadUrl()).build())
                .execute();
            InputStream inputStream = response.body().byteStream();
            FileOutputStream outputStream = new FileOutputStream(file)
        ) {
            IOUtils.copy(inputStream, outputStream);
        }

        plugin.setIsDownload(true);
        plugin.setIsEnable(true);
        plugin.setLocalVersionNumber(plugin.getVersionNumber());

        if (!Config.getBoolean(Keys.NewLauncher, false)) {
            XJavaFxSystemUtil.addJarClass(pluginJarInfo.getFile());
        }

        return file;
    }

    ////////////////////////////////////////////////////////////// 保存配置

    public void saveToFile() throws IOException {
        String json = JSON.toJSONString(this.pluginList, true);
        Path path = Paths.get(this.localPluginsPath);
        if (!Files.exists(path)) {
            Files.createFile(path);
        }
        Files.write(path, json.getBytes(DEFAULT_CHARSET));
    }

    public void saveToFileQuietly() {
        try {
            saveToFile();
        } catch (IOException e) {
            log.error("", e);
        }
    }

    ////////////////////////////////////////////////////////////// 下载进度

    private ProgressListener currentProgressListener;

    private class DownloadProgressInterceptor implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            Response originalResponse = chain.proceed(chain.request());
            return originalResponse.newBuilder()
                .body(new ProgressResponseBody(originalResponse.body(),
                    (bytesRead, contentLength, done) -> {
                        if (currentProgressListener != null) {
                            currentProgressListener.update(bytesRead, contentLength, done);
                        }
                    }
                ))
                .build();
        }
    }

    private static class ProgressResponseBody extends ResponseBody {

        private final ResponseBody responseBody;

        private final ProgressListener progressListener;

        private BufferedSource bufferedSource;

        ProgressResponseBody(ResponseBody responseBody, ProgressListener progressListener) {
            this.responseBody = responseBody;
            this.progressListener = progressListener;
        }

        @Override
        public MediaType contentType() {
            return responseBody.contentType();
        }

        @Override
        public long contentLength() {
            return responseBody.contentLength();
        }

        @Override
        public BufferedSource source() {
            if (bufferedSource == null) {
                bufferedSource = Okio.buffer(source(responseBody.source()));
            }
            return bufferedSource;
        }

        private Source source(Source source) {
            return new ForwardingSource(source) {
                long totalBytesRead = 0L;

                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    long bytesRead = super.read(sink, byteCount);
                    // read() returns the number of bytes read, or -1 if this source is exhausted.
                    totalBytesRead += bytesRead != -1 ? bytesRead : 0;
                    progressListener.update(totalBytesRead, responseBody.contentLength(), bytesRead == -1);
                    return bytesRead;
                }
            };
        }
    }

    interface ProgressListener {

        void update(long bytesRead, long contentLength, boolean done);
    }
}
