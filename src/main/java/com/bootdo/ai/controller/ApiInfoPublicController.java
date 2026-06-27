package com.bootdo.ai.controller;

import com.bootdo.ai.domain.AiModelConfigDO;
import com.bootdo.ai.service.AiModelConfigService;
import com.bootdo.ai.service.GlmApiKeyProvider;
import com.bootdo.ai.service.DynamicChatUpstreamConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开的模型配置查询接口，替代 nginx 静态 JSON 响应。
 * nginx 将 location = /api/info 改为 proxy_pass 到本服务即可。
 */
@RestController
public class ApiInfoPublicController {

    @Autowired
    private AiModelConfigService aiModelConfigService;

    @Autowired
    private GlmApiKeyProvider apiKeyProvider;

    @GetMapping("/api/info")
    public Map<String, Object> getApiInfo() {
        AiModelConfigDO textCfg  = aiModelConfigService.getByType("TEXT");
        AiModelConfigDO imgCfg   = aiModelConfigService.getByType("IMAGE");
        AiModelConfigDO vidCfg   = aiModelConfigService.getByType("VIDEO");
        AiModelConfigDO ieCfg    = aiModelConfigService.getByType("IMAGE_EDIT");

        // Effective text config (DB → remote polling fallback → local properties)
        DynamicChatUpstreamConfig effectiveText = apiKeyProvider.getChatConfig();

        Map<String, Object> data = new LinkedHashMap<>();

        // Legacy flat fields (backward compat)
        data.put("aiProvider", effectiveText.getProvider());
        data.put("aiBaseUrl",  effectiveText.getBaseUrl());
        data.put("aiApiKey",   effectiveText.getApiKey());
        data.put("aiModel",    effectiveText.getModel());
        data.put("imgApiKey",  effectiveText.getImgApiKey());

        // Per-type configs
        data.put("textConfig",      toConfigMap(textCfg));
        data.put("imageConfig",     toConfigMap(imgCfg));
        data.put("videoConfig",     toConfigMap(vidCfg));
        data.put("imageEditConfig", toConfigMap(ieCfg));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultCode",    200);
        result.put("resultMessage", "success");
        result.put("data",          data);
        return result;
    }

    private Map<String, Object> toConfigMap(AiModelConfigDO cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (cfg == null) {
            m.put("aiProvider", "");
            m.put("aiBaseUrl",  "");
            m.put("aiApiKey",   "");
            m.put("aiModel",    "");
            return m;
        }
        m.put("aiProvider", safe(cfg.getAiProvider()));
        m.put("aiBaseUrl",  safe(cfg.getAiBaseUrl()));
        m.put("aiApiKey",   safe(cfg.getAiApiKey()));
        m.put("aiModel",    safe(cfg.getAiModel()));
        return m;
    }

    private String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
