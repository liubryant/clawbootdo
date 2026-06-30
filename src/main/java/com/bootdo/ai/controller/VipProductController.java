package com.bootdo.ai.controller;

import com.bootdo.ai.service.NaviVipService;
import com.bootdo.common.utils.R;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;

@Controller
@RequestMapping("/ai/vip-product")
public class VipProductController {

    @Autowired(required = false)
    private NaviVipService naviVipService;

    private static final String PREFIX = "ai/vip_product";

    @GetMapping
    public String index() {
        return PREFIX + "/vip_product";
    }

    @GetMapping("/list")
    @ResponseBody
    public R list() {
        if (naviVipService == null) return R.error("VIP服务未启用");
        return R.ok().put("data", naviVipService.listAllProducts());
    }

    @PostMapping("/save")
    @ResponseBody
    public R save(@RequestParam String id,
                  @RequestParam String name,
                  @RequestParam String price,
                  @RequestParam int durationDays,
                  @RequestParam(required = false, defaultValue = "") String description,
                  @RequestParam(required = false, defaultValue = "0") int sortOrder) {
        if (naviVipService == null) return R.error("VIP服务未启用");
        if (StringUtils.isBlank(id) || StringUtils.isBlank(name) || StringUtils.isBlank(price)) {
            return R.error("ID、名称、价格不能为空");
        }
        try {
            BigDecimal priceVal = new BigDecimal(price.trim());
            if (priceVal.compareTo(BigDecimal.ZERO) < 0) return R.error("价格不能为负数");
            naviVipService.saveProduct(id.trim(), name.trim(), priceVal, durationDays, description.trim(), sortOrder);
            return R.ok("保存成功");
        } catch (NumberFormatException e) {
            return R.error("价格格式不正确");
        } catch (Exception e) {
            return R.error(e.getMessage());
        }
    }

    @PostMapping("/toggle")
    @ResponseBody
    public R toggle(@RequestParam String id, @RequestParam int enabled) {
        if (naviVipService == null) return R.error("VIP服务未启用");
        naviVipService.setProductEnabled(id, enabled);
        return R.ok(enabled == 1 ? "已启用" : "已禁用");
    }

    @PostMapping("/remove")
    @ResponseBody
    public R remove(@RequestParam String id) {
        if (naviVipService == null) return R.error("VIP服务未启用");
        naviVipService.removeProduct(id);
        return R.ok("删除成功");
    }
}
