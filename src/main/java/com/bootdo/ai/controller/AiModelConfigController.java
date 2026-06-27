package com.bootdo.ai.controller;

import com.bootdo.ai.domain.AiModelConfigDO;
import com.bootdo.ai.service.AiModelConfigService;
import com.bootdo.common.utils.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequestMapping("/ai/model-config")
public class AiModelConfigController {

    @Autowired
    private AiModelConfigService aiModelConfigService;

    private static final String PREFIX = "ai/model_config";

    @GetMapping
    public String index() {
        return PREFIX + "/model_config";
    }

    @GetMapping("/list")
    @ResponseBody
    public List<AiModelConfigDO> list() {
        return aiModelConfigService.listAll();
    }

    @PostMapping("/update")
    @ResponseBody
    public R update(AiModelConfigDO config) {
        if (config == null || config.getConfigType() == null || config.getConfigType().trim().isEmpty()) {
            return R.error("configType 不能为空");
        }
        int rows = aiModelConfigService.update(config);
        return rows > 0 ? R.ok() : R.error("更新失败，请确认配置类型存在");
    }

    @PostMapping("/batch-update")
    @ResponseBody
    public R batchUpdate(String textProvider, String textBaseUrl, String textApiKey, String textModel,
                          String imageProvider, String imageBaseUrl, String imageApiKey, String imageModel,
                          String videoProvider, String videoBaseUrl, String videoApiKey, String videoModel,
                          String ieProvider, String ieBaseUrl, String ieApiKey, String ieModel) {
        save("TEXT",       textProvider,  textBaseUrl,  textApiKey,  textModel);
        save("IMAGE",      imageProvider, imageBaseUrl, imageApiKey, imageModel);
        save("VIDEO",      videoProvider, videoBaseUrl, videoApiKey, videoModel);
        save("IMAGE_EDIT", ieProvider,    ieBaseUrl,    ieApiKey,    ieModel);
        return R.ok();
    }

    private void save(String type, String provider, String baseUrl, String apiKey, String model) {
        AiModelConfigDO cfg = new AiModelConfigDO();
        cfg.setConfigType(type);
        cfg.setAiProvider(provider);
        cfg.setAiBaseUrl(baseUrl);
        cfg.setAiApiKey(apiKey);
        cfg.setAiModel(model);
        cfg.setEnabled(1);
        aiModelConfigService.update(cfg);
    }
}
