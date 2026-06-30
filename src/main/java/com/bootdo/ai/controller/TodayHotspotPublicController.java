package com.bootdo.ai.controller;

import com.bootdo.ai.service.TodayHotspotService;
import com.bootdo.common.utils.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/im/bot/navi/hotspots")
public class TodayHotspotPublicController {
    private final TodayHotspotService hotspotService;

    public TodayHotspotPublicController(TodayHotspotService hotspotService) {
        this.hotspotService = hotspotService;
    }

    @GetMapping
    public R list() {
        return R.ok().put("data", hotspotService.listEnabled());
    }
}
