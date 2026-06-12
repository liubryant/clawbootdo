package com.bootdo.ai.controller;

import com.bootdo.ai.dto.SmsCodeRequest;
import com.bootdo.ai.service.SmsCodeService;
import com.bootdo.common.utils.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SmsCodeController {
    private final SmsCodeService smsCodeService;

    public SmsCodeController(SmsCodeService smsCodeService) {
        this.smsCodeService = smsCodeService;
    }

    @PostMapping("/im/bot/login-code")
    public R sendLoginCode(@RequestBody(required = false) SmsCodeRequest request) {
        if (request == null) {
            return R.error(400, "Request body is required");
        }
        try {
            int expiresIn = smsCodeService.sendLoginCode(request.getPhone());
            return R.ok("SMS code sent").put("data", new LoginCodeData(expiresIn));
        } catch (IllegalArgumentException ex) {
            return R.error(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return R.error(500, ex.getMessage());
        } catch (RuntimeException ex) {
            return R.error(500, ex.getMessage());
        }
    }

    public static class LoginCodeData {
        private int expiresIn;

        public LoginCodeData(int expiresIn) {
            this.expiresIn = expiresIn;
        }

        public int getExpiresIn() {
            return expiresIn;
        }

        public void setExpiresIn(int expiresIn) {
            this.expiresIn = expiresIn;
        }
    }
}
