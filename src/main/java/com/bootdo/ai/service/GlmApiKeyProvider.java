package com.bootdo.ai.service;

import com.bootdo.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天上游配置提供者：
 * - 优先返回远端动态配置（Refresher 写入）
 * - 如果远端配置尚未拉取成功，则回退本地 GLM 默认配置
 */
@Component
public class GlmApiKeyProvider {

    private final AiProperties aiProperties;

    /** 远端动态聊天配置（只存内存，不写回配置文件） */
    private final AtomicReference<DynamicChatUpstreamConfig> remoteChatConfig = new AtomicReference<DynamicChatUpstreamConfig>();

    /** 最后一次成功更新远端 key 的时间戳（ms），仅用于排查/监控 */
    private volatile long lastRemoteUpdateAt = 0L;

    public GlmApiKeyProvider(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public String getApiKey() {
        return getChatConfig().getApiKey();
    }

    public String getChatBaseUrl() {
        return getChatConfig().getBaseUrl();
    }

    public String getChatModel() {
        return getChatConfig().getModel();
    }

    public String getChatProvider() {
        return getChatConfig().getProvider();
    }

    public String getGlmRouteApiKey() {
        if (isGlmCompatibleProvider(getChatProvider())) {
            return safeTrim(getChatConfig().getApiKey());
        }
        return safeTrim(aiProperties.getGlm().getApiKey());
    }

    public String getGlmRouteBaseUrl() {
        if (isGlmCompatibleProvider(getChatProvider())) {
            return safeTrim(getChatConfig().getBaseUrl());
        }
        return safeTrim(aiProperties.getGlm().getBaseUrl());
    }

    public DynamicChatUpstreamConfig getChatConfig() {
        DynamicChatUpstreamConfig remote = remoteChatConfig.get();
        if (remote != null && !safeTrim(remote.getApiKey()).isEmpty()) {
            return mergeWithLocalDefaults(remote);
        }
        return buildLocalDefaultConfig();
    }

    /**
     * 写入远端聊天配置（仅当内容变化时写入）。
     *
     * @return true 表示发生了更新；false 表示无变化或无效输入
     */
    public boolean updateRemoteChatConfigIfChanged(DynamicChatUpstreamConfig newConfig) {
        if (newConfig == null || safeTrim(newConfig.getApiKey()).isEmpty()) {
            return false;
        }
        DynamicChatUpstreamConfig normalized = mergeWithLocalDefaults(newConfig);
        DynamicChatUpstreamConfig old = remoteChatConfig.get();
        if (isSameConfig(old, normalized)) {
            return false;
        }
        remoteChatConfig.set(normalized);
        lastRemoteUpdateAt = System.currentTimeMillis();
        return true;
    }

    public long getLastRemoteUpdateAt() {
        return lastRemoteUpdateAt;
    }

    private String safeTrim(String v) {
        return v == null ? "" : v.trim();
    }

    private DynamicChatUpstreamConfig buildLocalDefaultConfig() {
        return new DynamicChatUpstreamConfig(
                "glm",
                safeTrim(aiProperties.getGlm().getBaseUrl()),
                safeTrim(aiProperties.getGlm().getApiKey()),
                safeTrim(aiProperties.getGlm().getModel())
        );
    }

    private DynamicChatUpstreamConfig mergeWithLocalDefaults(DynamicChatUpstreamConfig config) {
        DynamicChatUpstreamConfig local = buildLocalDefaultConfig();
        return new DynamicChatUpstreamConfig(
                safeTrim(config.getProvider()).isEmpty() ? local.getProvider() : safeTrim(config.getProvider()),
                safeTrim(config.getBaseUrl()).isEmpty() ? local.getBaseUrl() : safeTrim(config.getBaseUrl()),
                safeTrim(config.getApiKey()).isEmpty() ? local.getApiKey() : safeTrim(config.getApiKey()),
                safeTrim(config.getModel()).isEmpty() ? local.getModel() : safeTrim(config.getModel())
        );
    }

    private boolean isSameConfig(DynamicChatUpstreamConfig oldConfig, DynamicChatUpstreamConfig newConfig) {
        if (oldConfig == null && newConfig == null) {
            return true;
        }
        if (oldConfig == null || newConfig == null) {
            return false;
        }
        return safeTrim(oldConfig.getProvider()).equals(safeTrim(newConfig.getProvider()))
                && safeTrim(oldConfig.getBaseUrl()).equals(safeTrim(newConfig.getBaseUrl()))
                && safeTrim(oldConfig.getApiKey()).equals(safeTrim(newConfig.getApiKey()))
                && safeTrim(oldConfig.getModel()).equals(safeTrim(newConfig.getModel()));
    }

    private boolean isGlmCompatibleProvider(String provider) {
        String normalized = safeTrim(provider).toLowerCase();
        return normalized.isEmpty()
                || "glm".equals(normalized)
                || "bigmodel".equals(normalized)
                || "zhipu".equals(normalized)
                || "zhipuai".equals(normalized);
    }
}
