package com.bootdo.ai.service;

import com.bootdo.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 定时从 /api/info 拉取最新聊天上游配置，并在配置变化时更新。
 */
@Component
public class GlmApiKeyRefresher {

    private static final Logger log = LoggerFactory.getLogger(GlmApiKeyRefresher.class);

    private final AiProperties aiProperties;
    private final ApiInfoClient apiInfoClient;
    private final GlmApiKeyProvider apiKeyProvider;

    public GlmApiKeyRefresher(AiProperties aiProperties,
                             ApiInfoClient apiInfoClient,
                             GlmApiKeyProvider apiKeyProvider) {
        this.aiProperties = aiProperties;
        this.apiInfoClient = apiInfoClient;
        this.apiKeyProvider = apiKeyProvider;
    }

    /**
     * 启动后先尝试拉取一次（不阻塞启动失败，只记录日志）。
     */
    @PostConstruct
    public void init() {
        refreshOnce("startup");
    }

    /**
     * 定时刷新：默认每 2 分钟一次（可通过 ai.glm.apiKeyRefreshMs 覆盖）。
     *
     * fixedDelay：上一轮执行结束后再等待 apiKeyRefreshMs。
     */
    @Scheduled(fixedDelayString = "#{@aiProperties.glm.apiKeyRefreshMs}")
    public void scheduledRefresh() {
        refreshOnce("scheduled");
    }

    private void refreshOnce(String reason) {
        String url = safeTrim(aiProperties.getGlm().getApiInfoUrl());
        if (url.isEmpty()) {
            // 未配置则不启用动态 key 刷新，继续用本地默认 key
            return;
        }

        try {
            DynamicChatUpstreamConfig newConfig = apiInfoClient.fetchChatUpstreamConfig();
            boolean changed = apiKeyProvider.updateRemoteChatConfigIfChanged(newConfig);
            if (changed) {
                DynamicChatUpstreamConfig applied = apiKeyProvider.getChatConfig();
                log.info("Chat upstream config refreshed (reason={}, fromUrl={}, provider={}, model={}, baseUrl={}), lastUpdateAt={}",
                        reason,
                        url,
                        applied.getProvider(),
                        applied.getModel(),
                        applied.getBaseUrl(),
                        apiKeyProvider.getLastRemoteUpdateAt());
            }
        } catch (Exception ex) {
            // 刷新失败不影响对话：继续使用上一次缓存 key 或本地默认 key
            log.warn("Chat upstream config refresh failed (reason={}, fromUrl={}): {}",
                    reason, url, ex.getMessage());
        }
    }

    private String safeTrim(String v) {
        return v == null ? "" : v.trim();
    }
}
