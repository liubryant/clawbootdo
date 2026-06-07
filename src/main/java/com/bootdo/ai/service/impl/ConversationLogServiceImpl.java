package com.bootdo.ai.service.impl;

import com.bootdo.ai.dao.ConversationLogDao;
import com.bootdo.ai.domain.ConversationLogDO;
import com.bootdo.ai.service.ConversationLogService;
import com.bootdo.common.domain.PageDO;
import com.bootdo.common.utils.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationLogServiceImpl implements ConversationLogService {
    private static final Logger log = LoggerFactory.getLogger(ConversationLogServiceImpl.class);

    @Autowired
    ConversationLogDao conversationLogDao;

    @Async
    @Override
    public void save(ConversationLogDO conversationLog) {
        if (conversationLog == null) {
            return;
        }
        try {
            conversationLogDao.save(conversationLog);
        } catch (Exception ex) {
            log.warn("Save AI conversation log failed: {}", ex.getMessage());
        }
    }

    @Override
    public PageDO<ConversationLogDO> queryList(Query query) {
        int total = conversationLogDao.count(query);
        List<ConversationLogDO> rows = conversationLogDao.list(query);
        PageDO<ConversationLogDO> page = new PageDO<>();
        page.setTotal(total);
        page.setRows(rows);
        return page;
    }

    @Override
    public int remove(Long id) {
        return conversationLogDao.remove(id);
    }

    @Override
    public int batchRemove(Long[] ids) {
        return conversationLogDao.batchRemove(ids);
    }
}
