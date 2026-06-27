package com.bootdo.ai.service;

import com.bootdo.ai.config.AiProperties;
import com.bootdo.ai.domain.AiModelConfigDO;
import com.bootdo.ai.service.AiModelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天上游配置提供者：
 * - DB 配置优先（通过 AiModelConfigService 读取）
 * - 其次返回远端动态配置（Refresher 写入）
 * - 最后回退本地 application.yml 默认配置
 */
@Component
public class GlmApiKeyProvider {

    private final AiProperties aiProperties;

    /** 远端动态聊天配置（只存内存，不写回配置文件） */
    private final AtomicReference<DynamicChatUpstreamConfig> remoteChatConfig = new AtomicReference<DynamicChatUpstreamConfig>();

    /** 远端动态图片生成 apiKey（来自 api/info 的 imgApiKey 字段，为空则回退本地 GLM key） */
    private final AtomicReference<String> remoteImgApiKey = new AtomicReference<>("");

    /** 最后一次成功更新远端 key 的时间戳（ms），仅用于排查/监控 */
    private volatile long lastRemoteUpdateAt = 0L;

    @Autowired(required = false)
    private AiModelConfigService modelConfigService;

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
        String imgKey = remoteImgApiKey.get();
        if (imgKey != null && !imgKey.isEmpty()) {
            return imgKey;
        }
        if (isGlmCompatibleProvider(getChatProvider())) {
            return safeTrim(getChatConfig().getApiKey());
        }
        return safeTrim(aiProperties.getGlm().getApiKey());
    }

    public String getImageRouteApiKey() {
        // 本地 siliconflow key 优先：明确为图生图配置，不应被远端通用 imgApiKey 覆盖
        String siliconflowKey = aiProperties.getSiliconflow() == null ? "" : safeTrim(aiProperties.getSiliconflow().getApiKey());
        if (!siliconflowKey.isEmpty()) {
            return siliconflowKey;
        }
        String imgKey = remoteImgApiKey.get();
        if (imgKey != null && !imgKey.isEmpty()) {
            return safeTrim(imgKey);
        }
        String provider = safeTrim(getChatProvider()).toLowerCase();
        if ("qwen".equals(provider) || "siliconflow".equals(provider)) {
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

    /** 图片生成路由配置（DB → 现有 GLM 路由逻辑） */
    public DynamicChatUpstreamConfig getImageRouteConfig() {
        DynamicChatUpstreamConfig db = loadDbConfig("IMAGE");
        if (db != null) return db;
        String model = safeTrim(aiProperties.getGlm().getImageModel());
        if (model.isEmpty()) model = "glm-image";
        return new DynamicChatUpstreamConfig("glm", getGlmRouteBaseUrl(), getGlmRouteApiKey(), model);
    }

    /** 视频生成路由配置（DB → 现有 GLM 路由逻辑） */
    public DynamicChatUpstreamConfig getVideoRouteConfig() {
        DynamicChatUpstreamConfig db = loadDbConfig("VIDEO");
        if (db != null) return db;
        String model = safeTrim(aiProperties.getGlm().getVideoModel());
        if (model.isEmpty()) model = "cogvideox-3";
        return new DynamicChatUpstreamConfig("glm", getGlmRouteBaseUrl(), getGlmRouteApiKey(), model);
    }

    /** 图生图路由配置（DB → 现有 SiliconFlow/本地 key 逻辑） */
    public DynamicChatUpstreamConfig getImageEditRouteConfig() {
        DynamicChatUpstreamConfig db = loadDbConfig("IMAGE_EDIT");
        if (db != null) return db;
        AiProperties.Siliconflow sf = aiProperties.getSiliconflow();
        String sfBase  = sf == null ? "" : safeTrim(sf.getBaseUrl());
        if (sfBase.isEmpty()) sfBase = "https://api.siliconflow.cn/v1";
        String sfModel = sf == null ? "" : safeTrim(sf.getImageEditModel());
        if (sfModel.isEmpty()) sfModel = "Qwen/Qwen-Image-Edit-2509";
        return new DynamicChatUpstreamConfig("siliconflow", sfBase, getImageRouteApiKey(), sfModel);
    }

    /** 从 DB 读取指定类型配置；apiKey 为空则返回 null（表示使用现有回退逻辑）。 */
    private DynamicChatUpstreamConfig loadDbConfig(String type) {
        if (modelConfigService == null) return null;
        try {
            AiModelConfigDO cfg = modelConfigService.getByType(type);
            if (cfg == null || safeTrim(cfg.getAiApiKey()).isEmpty()) return null;
            return new DynamicChatUpstreamConfig(
                    safeTrim(cfg.getAiProvider()),
                    safeTrim(cfg.getAiBaseUrl()),
                    safeTrim(cfg.getAiApiKey()),
                    safeTrim(cfg.getAiModel())
            );
        } catch (Exception e) {
            return null;
        }
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
        String newImgKey = safeTrim(newConfig.getImgApiKey());
        boolean imgKeyChanged = !newImgKey.equals(safeTrim(remoteImgApiKey.get()));
        if (isSameConfig(old, normalized) && !imgKeyChanged) {
            return false;
        }
        remoteChatConfig.set(normalized);
        remoteImgApiKey.set(newImgKey);
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
