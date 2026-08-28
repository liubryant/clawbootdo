package com.bootdo.ai.controller;

import com.bootdo.ai.dto.AppleBindRequest;
import com.bootdo.ai.dto.AppleVerifyRequest;
import com.bootdo.ai.service.AppAccessTokenService;
import com.bootdo.ai.service.SleepVipService;
import com.bootdo.common.utils.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/im/bot/sleep/vip")
public class SleepVipController {
    private static final Logger log = LoggerFactory.getLogger(SleepVipController.class);

    private final SleepVipService vipService;
    private final AppAccessTokenService tokenService;

    public SleepVipController(SleepVipService vipService, AppAccessTokenService tokenService) {
        this.vipService = vipService;
        this.tokenService = tokenService;
    }

    @GetMapping("/apple/membership")
    public R appleMembership(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String phone = authenticatedPhone(authorization);
        if (phone == null) return R.error(401, "登录状态已失效，请重新登录");
        try {
            return R.ok().put("data", vipService.getAppleMembershipStatus(phone));
        } catch (IllegalArgumentException e) {
            return R.error(404, e.getMessage());
        } catch (RuntimeException e) {
            log.error("时光睡眠 Apple 订阅状态查询失败", e);
            return R.error(503, "会员状态查询失败，请稍后重试");
        }
    }

    @PostMapping("/apple/verify")
    public R appleVerify(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestBody(required = false) AppleVerifyRequest request) {
        String phone = authenticatedPhone(authorization);
        if (phone == null && authorization != null && !authorization.trim().isEmpty()) {
            return R.error(401, "登录状态已失效，请重新登录");
        }
        if (request == null || request.getJws() == null || request.getJws().isEmpty()) {
            return R.error(400, "缺少支付凭证");
        }
        try {
            return R.ok().put("data", vipService.verifyApplePurchase(
                    phone, request.getAppleProductId(), request.getTransactionId(), request.getJws()));
        } catch (SecurityException e) {
            log.warn("时光睡眠苹果内购凭证校验失败: {}", e.getMessage());
            return R.error(400, "支付凭证校验未通过");
        } catch (IllegalArgumentException e) {
            return R.error(400, e.getMessage());
        } catch (RuntimeException e) {
            log.error("时光睡眠苹果内购会员发放失败", e);
            return R.error(503, "会员权益同步失败，请点击恢复购买重试");
        }
    }

    @PostMapping("/apple/bind")
    public R bindAppleMembership(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestBody(required = false) AppleBindRequest request) {
        String phone = authenticatedPhone(authorization);
        if (phone == null) return R.error(401, "登录状态已失效，请重新登录");
        String guestToken = request == null ? null : request.getGuestAccessToken();
        String guestPhone = tokenService.verifyAndGetPhone(guestToken);
        try {
            return R.ok().put("data", vipService.bindAppleGuestMembership(phone, guestPhone));
        } catch (IllegalArgumentException e) {
            return R.error(400, e.getMessage());
        } catch (RuntimeException e) {
            log.error("时光睡眠 Apple 游客会员绑定失败", e);
            return R.error(503, "会员权益同步失败，请稍后重试");
        }
    }

    @GetMapping(value = "/agreement/member", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> memberAgreement() {
        return htmlAgreement("static/docs/sleep/vip_agreement.html");
    }

    @GetMapping(value = "/agreement/auto-renew", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> autoRenewAgreement() {
        return htmlAgreement("static/docs/sleep/auto_renew_agreement.html");
    }

    private ResponseEntity<String> htmlAgreement(String classpath) {
        try {
            String html = StreamUtils.copyToString(
                    new ClassPathResource(classpath).getInputStream(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body(html);
        } catch (Exception e) {
            log.error("时光睡眠会员协议加载失败 path={}", classpath, e);
            return ResponseEntity.status(500)
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body("<html><body>协议页面加载失败，请稍后重试。</body></html>");
        }
    }

    private String authenticatedPhone(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return tokenService.verifyAndGetPhone(authorization.substring(7).trim());
    }
}
