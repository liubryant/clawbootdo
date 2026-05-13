package com.bootdo.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    /** 是否启用兼容接口 */
    private boolean enabled = true;

    /** 可选：网关透传令牌，配置后将强制校验 Authorization: Bearer xxx */
    private String gatewayToken;

    private int connectTimeoutMs = 15000;
    private int readTimeoutMs = 600000;

    private Glm glm = new Glm();

    public static class Glm {
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        private String apiKey;
        private String model = "glm-4.7";
        private String imageModel = "glm-image";
        private String videoModel = "cogvideox-3";
        private String imageSize = "1280x1280";
        private String imageQuality = "hd";
        private Boolean imageWatermarkEnabled = false;
        private String videoQuality = "quality";
        private Boolean videoWithAudio = true;
        private String videoSize = "1920x1080";
        private Integer videoFps = 30;
        private Long videoPollIntervalMs = 5000L;
        private Long videoPollTimeoutMs = 600000L;

        /** 动态获取 apiKey 的信息接口（例如：http://cjym123.cn/api/info） */
        private String apiInfoUrl;

        /** 动态 apiKey 刷新间隔（毫秒）。默认 2 分钟 */
        private long apiKeyRefreshMs = 120000L;

        /** 拉取 apiInfo 的连接超时（毫秒）。建议较小，如 2000ms */
        private int apiInfoConnectTimeoutMs = 2000;

        /** 拉取 apiInfo 的读取超时（毫秒）。建议较小，如 3000ms */
        private int apiInfoReadTimeoutMs = 3000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getImageModel() {
            return imageModel;
        }

        public void setImageModel(String imageModel) {
            this.imageModel = imageModel;
        }

        public String getVideoModel() {
            return videoModel;
        }

        public void setVideoModel(String videoModel) {
            this.videoModel = videoModel;
        }

        public String getImageSize() {
            return imageSize;
        }

        public void setImageSize(String imageSize) {
            this.imageSize = imageSize;
        }

        public String getImageQuality() {
            return imageQuality;
        }

        public void setImageQuality(String imageQuality) {
            this.imageQuality = imageQuality;
        }

        public Boolean getImageWatermarkEnabled() {
            return imageWatermarkEnabled;
        }

        public void setImageWatermarkEnabled(Boolean imageWatermarkEnabled) {
            this.imageWatermarkEnabled = imageWatermarkEnabled;
        }

        public String getVideoQuality() {
            return videoQuality;
        }

        public void setVideoQuality(String videoQuality) {
            this.videoQuality = videoQuality;
        }

        public Boolean getVideoWithAudio() {
            return videoWithAudio;
        }

        public void setVideoWithAudio(Boolean videoWithAudio) {
            this.videoWithAudio = videoWithAudio;
        }

        public String getVideoSize() {
            return videoSize;
        }

        public void setVideoSize(String videoSize) {
            this.videoSize = videoSize;
        }

        public Integer getVideoFps() {
            return videoFps;
        }

        public void setVideoFps(Integer videoFps) {
            this.videoFps = videoFps;
        }

        public Long getVideoPollIntervalMs() {
            return videoPollIntervalMs;
        }

        public void setVideoPollIntervalMs(Long videoPollIntervalMs) {
            this.videoPollIntervalMs = videoPollIntervalMs;
        }

        public Long getVideoPollTimeoutMs() {
            return videoPollTimeoutMs;
        }

        public void setVideoPollTimeoutMs(Long videoPollTimeoutMs) {
            this.videoPollTimeoutMs = videoPollTimeoutMs;
        }

        public String getApiInfoUrl() {
            return apiInfoUrl;
        }

        public void setApiInfoUrl(String apiInfoUrl) {
            this.apiInfoUrl = apiInfoUrl;
        }

        public long getApiKeyRefreshMs() {
            return apiKeyRefreshMs;
        }

        public void setApiKeyRefreshMs(long apiKeyRefreshMs) {
            this.apiKeyRefreshMs = apiKeyRefreshMs;
        }

        public int getApiInfoConnectTimeoutMs() {
            return apiInfoConnectTimeoutMs;
        }

        public void setApiInfoConnectTimeoutMs(int apiInfoConnectTimeoutMs) {
            this.apiInfoConnectTimeoutMs = apiInfoConnectTimeoutMs;
        }

        public int getApiInfoReadTimeoutMs() {
            return apiInfoReadTimeoutMs;
        }

        public void setApiInfoReadTimeoutMs(int apiInfoReadTimeoutMs) {
            this.apiInfoReadTimeoutMs = apiInfoReadTimeoutMs;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGatewayToken() {
        return gatewayToken;
    }

    public void setGatewayToken(String gatewayToken) {
        this.gatewayToken = gatewayToken;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public Glm getGlm() {
        return glm;
    }

    public void setGlm(Glm glm) {
        this.glm = glm;
    }
}
