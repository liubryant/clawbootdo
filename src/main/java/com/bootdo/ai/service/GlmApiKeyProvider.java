package com.bootdo.ai.service;

import com.bootdo.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * GLM apiKey 提供者：
 * - 优先返回远端动态 key（ApiKeyRefresher 写入）
 * - 如果远端 key 尚未拉取成功，则回退本地配置的默认 key（ai.glm.apiKey）
 */
@Component
public class GlmApiKeyProvider {

    private final AiProperties aiProperties;

    /** 远端动态 key（只存内存，不写回配置文件） */
    private final AtomicReference<String> remoteApiKey = new AtomicReference<>();

    /** 最后一次成功更新远端 key 的时间戳（ms），仅用于排查/监控 */
    private volatile long lastRemoteUpdateAt = 0L;

    public GlmApiKeyProvider(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * 对外统一取 key 的入口。
     */
    public String getApiKey() {
        String remote = safeTrim(remoteApiKey.get());
        if (!remote.isEmpty()) {
            return remote;
        }
        return safeTrim(aiProperties.getGlm().getApiKey());
    }

    /**
     * 写入远端 key（仅当变化时写入）。
     *
     * @return true 表示发生了更新（key 发生变化）；false 表示无变化或无效输入
     */
    public boolean updateRemoteKeyIfChanged(String newKey) {
        newKey = safeTrim(newKey);
        if (newKey.isEmpty()) {
            return false;
        }
        String old = safeTrim(remoteApiKey.get());
        if (newKey.equals(old)) {
            return false;
        }
        remoteApiKey.set(newKey);
        lastRemoteUpdateAt = System.currentTimeMillis();
        return true;
    }

    public long getLastRemoteUpdateAt() {
        return lastRemoteUpdateAt;
    }

    private String safeTrim(String v) {
        return v == null ? "" : v.trim();
    }
}
