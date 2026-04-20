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
