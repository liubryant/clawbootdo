package com.bootdo.ai.controller;

import com.bootdo.ai.dao.AppModelConfigDao;
import com.bootdo.ai.domain.AppModelConfigDO;
import com.bootdo.common.utils.R;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/inspireplanet/model-config")
public class InspirePlanetModelConfigController {
    private static final String APP_CODE = "inspireplanet";
    private final AppModelConfigDao dao;

    public InspirePlanetModelConfigController(AppModelConfigDao dao) { this.dao = dao; }

    @GetMapping
    public String index() { return "inspireplanet/model_config"; }

    @GetMapping("/get")
    @ResponseBody
    public AppModelConfigDO get(HttpServletResponse response) {
        // 该页面受后台登录鉴权保护；按模型管理需求回显已保存的 Key，
        // 同时禁止浏览器和中间代理缓存包含密钥的响应。
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return dao.get(APP_CODE, "TEXT");
    }

    @PostMapping("/update")
    @ResponseBody
    public R update(AppModelConfigDO config) {
        if (config == null || blank(config.getAiBaseUrl()) || blank(config.getAiModel())) {
            return R.error("接口地址和模型不能为空");
        }
        if (!config.getAiBaseUrl().startsWith("https://")) {
            return R.error("接口地址必须使用 HTTPS");
        }
        config.setAppCode(APP_CODE);
        config.setConfigType("TEXT");
        config.setEnabled(1);
        return dao.update(config) > 0 ? R.ok() : R.error("保存失败");
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
