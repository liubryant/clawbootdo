package com.bootdo.ai.dao;

import com.bootdo.ai.domain.AppModelConfigDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AppModelConfigDao {
    AppModelConfigDO get(@Param("appCode") String appCode, @Param("configType") String configType);
    int update(AppModelConfigDO config);
}
