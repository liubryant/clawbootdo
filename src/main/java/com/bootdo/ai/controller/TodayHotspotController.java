package com.bootdo.ai.controller;

import com.bootdo.ai.service.TodayHotspotService;
import com.bootdo.common.utils.R;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/ai/today-hotspot")
public class TodayHotspotController {
    private final TodayHotspotService hotspotService;

    public TodayHotspotController(TodayHotspotService hotspotService) {
        this.hotspotService = hotspotService;
    }

    @GetMapping
    public String index() {
        return "ai/today_hotspot/today_hotspot";
    }

    @GetMapping("/list")
    @ResponseBody
    public R list() {
        return R.ok().put("data", hotspotService.listAll());
    }

    @PostMapping("/save")
    @ResponseBody
    public R save(@RequestParam(required = false) Long id,
                  @RequestParam String title,
                  @RequestParam String subtitle,
                  @RequestParam String promptTemplate,
                  @RequestParam(required = false, defaultValue = "0") int sortOrder) {
        if (StringUtils.isBlank(title) || StringUtils.isBlank(subtitle) || StringUtils.isBlank(promptTemplate)) {
            return R.error("标题、副标题和提示词内容不能为空");
        }
        if (title.trim().length() > 100 || subtitle.trim().length() > 100 || promptTemplate.trim().length() > 255) {
            return R.error("标题/副标题最多100字，提示词最多255字");
        }
        try {
            hotspotService.save(id, title.trim(), subtitle.trim(), promptTemplate.trim(), sortOrder);
            return R.ok("保存成功");
        } catch (Exception e) {
            return R.error(e.getMessage());
        }
    }

    @PostMapping("/toggle")
    @ResponseBody
    public R toggle(@RequestParam Long id, @RequestParam int enabled) {
        try {
            hotspotService.setEnabled(id, enabled);
            return R.ok(enabled == 1 ? "已启用" : "已禁用");
        } catch (Exception e) {
            return R.error(e.getMessage());
        }
    }

    @PostMapping("/remove")
    @ResponseBody
    public R remove(@RequestParam Long id) {
        try {
            hotspotService.remove(id);
            return R.ok("删除成功");
        } catch (Exception e) {
            return R.error(e.getMessage());
        }
    }
}
