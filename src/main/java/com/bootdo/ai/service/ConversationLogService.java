package com.bootdo.ai.service;

import com.bootdo.ai.domain.ConversationLogDO;
import com.bootdo.common.domain.PageDO;
import com.bootdo.common.utils.Query;
import org.springframework.stereotype.Service;

@Service
public interface ConversationLogService {
    void save(ConversationLogDO log);

    PageDO<ConversationLogDO> queryList(Query query);

    int remove(Long id);

    int batchRemove(Long[] ids);
}
