package com.bootdo.ai.dao;

import com.bootdo.ai.domain.ConversationLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface ConversationLogDao {
    ConversationLogDO get(Long id);

    List<ConversationLogDO> list(Map<String, Object> map);

    int count(Map<String, Object> map);

    int save(ConversationLogDO log);

    int remove(Long id);

    int batchRemove(Long[] ids);
}
