package com.bootdo.ai.service;

/**
 * 聊天上游的动态运行时配置。
 *
 * 说明：
 * - apiKey 用于 /v1/chat/completions 及其 token usage 路径
 * - imgApiKey 用于图片生成，优先于本地 GLM 配置；为空则回退原有逻辑
 */
public class DynamicChatUpstreamConfig {

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String imgApiKey;

    public DynamicChatUpstreamConfig(String provider, String baseUrl, String apiKey, String model) {
        this(provider, baseUrl, apiKey, model, "");
    }

    public DynamicChatUpstreamConfig(String provider, String baseUrl, String apiKey, String model, String imgApiKey) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.imgApiKey = imgApiKey == null ? "" : imgApiKey;
    }

    public String getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public String getImgApiKey() {
        return imgApiKey;
    }
}
