package com.bootdo.ai.service;

import com.bootdo.ai.domain.AiModelConfigDO;

import java.util.List;

public interface AiModelConfigService {
    List<AiModelConfigDO> listAll();
    AiModelConfigDO getByType(String configType);
    int update(AiModelConfigDO config);
}
