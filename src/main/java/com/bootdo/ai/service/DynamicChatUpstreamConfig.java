package com.bootdo.ai.service;

/**
 * 聊天上游的动态运行时配置。
 *
 * 说明：
 * - 仅用于 /v1/chat/completions 及其 token usage 路径
 * - 图片、视频相关能力仍沿用本地 GLM 配置
 */
public class DynamicChatUpstreamConfig {

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public DynamicChatUpstreamConfig(String provider, String baseUrl, String apiKey, String model) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
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
}
