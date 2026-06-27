package com.bootdo.ai.service.impl;

import com.bootdo.ai.dao.AiModelConfigDao;
import com.bootdo.ai.domain.AiModelConfigDO;
import com.bootdo.ai.service.AiModelConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiModelConfigServiceImpl implements AiModelConfigService {

    private final AiModelConfigDao dao;

    public AiModelConfigServiceImpl(AiModelConfigDao dao) {
        this.dao = dao;
    }

    @Override
    public List<AiModelConfigDO> listAll() {
        return dao.listAll();
    }

    @Override
    public AiModelConfigDO getByType(String configType) {
        return dao.getByType(configType);
    }

    @Override
    public int update(AiModelConfigDO config) {
        return dao.update(config);
    }
}
