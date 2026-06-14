package com.bootdo.ai.controller;

import com.bootdo.ai.domain.AppUserDO;
import com.bootdo.ai.service.AppUserService;
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

@RequestMapping("/ai/appuser")
@Controller
public class AppUserController {
    @Autowired
    AppUserService appUserService;

    String prefix = "ai/appuser";

    @GetMapping()
    String appUser() {
        return prefix + "/appuser";
    }

    @GetMapping("/add")
    String add() {
        return prefix + "/add";
    }

    @ResponseBody
    @GetMapping("/list")
    PageDO<AppUserDO> list(@RequestParam Map<String, Object> params) {
        Query query = new Query(params);
        return appUserService.queryList(query);
    }

    @ResponseBody
    @PostMapping("/save")
    R save(@RequestParam String phone, @RequestParam String password) {
        try {
            appUserService.addUser(phone, password);
            return R.ok("新增成功");
        } catch (IllegalArgumentException ex) {
            return R.error(400, ex.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/remove")
    R remove(Long id) {
        appUserService.removeUser(id);
        return R.ok("删除成功");
    }

    @ResponseBody
    @PostMapping("/batchRemove")
    R batchRemove(@RequestParam("ids[]") Long[] ids) {
        appUserService.batchRemove(ids);
        return R.ok("删除成功");
    }
}
