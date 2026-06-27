package com.bootdo.ai.dao;

import com.bootdo.ai.domain.AiModelConfigDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AiModelConfigDao {
    List<AiModelConfigDO> listAll();
    AiModelConfigDO getByType(String configType);
    int update(AiModelConfigDO config);
}
