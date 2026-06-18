package com.bootdo.ai.dao;

import com.bootdo.ai.domain.AppUserDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface AppUserDao {
    AppUserDO getByPhone(String phone);

    int save(AppUserDO user);

    int updatePassword(AppUserDO user);

    int updateLoginInfo(AppUserDO user);

    List<AppUserDO> list(Map<String, Object> map);

    int count(Map<String, Object> map);

    int remove(Long id);

    int batchRemove(Long[] ids);

    int removeByPhone(String phone);
}
