package com.bootdo.ai.controller;

import com.bootdo.ai.domain.ConversationLogDO;
import com.bootdo.ai.service.ConversationLogService;
import com.bootdo.common.domain.PageDO;
import com.bootdo.common.utils.Query;
import com.bootdo.common.utils.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@RequestMapping("/ai/conversation")
@Controller
public class ConversationLogController {
    @Autowired
    ConversationLogService conversationLogService;

    String prefix = "ai/conversation";

    @GetMapping()
    String conversation() {
        return prefix + "/conversation";
    }

    @ResponseBody
    @GetMapping("/list")
    PageDO<ConversationLogDO> list(@RequestParam Map<String, Object> params) {
        Query query = new Query(params);
        return conversationLogService.queryList(query);
    }

    @ResponseBody
    @PostMapping("/remove")
    R remove(Long id) {
        if (conversationLogService.remove(id) > 0) {
            return R.ok();
        }
        return R.error();
    }

    @ResponseBody
    @PostMapping("/batchRemove")
    R batchRemove(@RequestParam("ids[]") Long[] ids) {
        int r = conversationLogService.batchRemove(ids);
        if (r > 0) {
            return R.ok();
        }
        return R.error();
    }
}
