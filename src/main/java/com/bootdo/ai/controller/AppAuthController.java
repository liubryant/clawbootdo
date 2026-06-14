package com.bootdo.ai.controller;

import com.bootdo.ai.dto.LoginByCodeRequest;
import com.bootdo.ai.dto.LoginByPasswordRequest;
import com.bootdo.ai.dto.SetPasswordRequest;
import com.bootdo.ai.service.AppUserService;
import com.bootdo.ai.vo.AppDeviceInfo;
import com.bootdo.common.utils.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppAuthController {
    private final AppUserService appUserService;

    public AppAuthController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @PostMapping("/im/bot/login-by-code")
    public R loginByCode(@RequestBody(required = false) LoginByCodeRequest request) {
        if (request == null) {
            return R.error(400, "Request body is required");
        }
        try {
            AppDeviceInfo deviceInfo = new AppDeviceInfo(request.getDeviceModel(), request.getOsVersion(), request.getAppName());
            return R.ok("登录成功").put("data", appUserService.loginByCode(request.getPhone(), request.getCode(), deviceInfo));
        } catch (IllegalArgumentException ex) {
            return R.error(400, ex.getMessage());
        } catch (RuntimeException ex) {
            return R.error(500, ex.getMessage());
        }
    }

    @PostMapping("/im/bot/login-by-password")
    public R loginByPassword(@RequestBody(required = false) LoginByPasswordRequest request) {
        if (request == null) {
            return R.error(400, "Request body is required");
        }
        try {
            AppDeviceInfo deviceInfo = new AppDeviceInfo(request.getDeviceModel(), request.getOsVersion(), request.getAppName());
            return R.ok("登录成功").put("data", appUserService.loginByPassword(request.getPhone(), request.getPassword(), deviceInfo));
        } catch (IllegalArgumentException ex) {
            return R.error(400, ex.getMessage());
        } catch (RuntimeException ex) {
            return R.error(500, ex.getMessage());
        }
    }

    @PostMapping("/im/bot/set-password")
    public R setPassword(@RequestBody(required = false) SetPasswordRequest request) {
        if (request == null) {
            return R.error(400, "Request body is required");
        }
        try {
            return R.ok("密码设置成功").put("data", appUserService.setPassword(request.getPhone(), request.getCode(), request.getPassword()));
        } catch (IllegalArgumentException ex) {
            return R.error(400, ex.getMessage());
        } catch (RuntimeException ex) {
            return R.error(500, ex.getMessage());
        }
    }
}
