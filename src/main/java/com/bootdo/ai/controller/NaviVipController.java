package com.bootdo.ai.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.dto.CreateNaviVipOrderRequest;
import com.bootdo.ai.service.AlipayClient;
import com.bootdo.ai.service.AppAccessTokenService;
import com.bootdo.ai.service.NaviVipService;
import com.bootdo.ai.service.WeChatPayClient;
import com.bootdo.common.utils.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/im/bot/navi/vip")
public class NaviVipController {
    private static final Logger log = LoggerFactory.getLogger(NaviVipController.class);

    private final NaviVipService vipService;
    private final AppAccessTokenService tokenService;
    private final WeChatPayClient weChatPayClient;
    private final AlipayClient alipayClient;

    public NaviVipController(NaviVipService vipService, AppAccessTokenService tokenService,
                              WeChatPayClient weChatPayClient, AlipayClient alipayClient) {
        this.vipService = vipService;
        this.tokenService = tokenService;
        this.weChatPayClient = weChatPayClient;
        this.alipayClient = alipayClient;
    }

    @GetMapping("/membership")
    public R membership(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String phone = authenticatedPhone(authorization);
        if (phone == null) {
            return R.error(401, "登录状态已失效，请重新登录");
        }
        try {
            return R.ok().put("data", vipService.getMembershipStatus(phone));
        } catch (IllegalArgumentException e) {
            return R.error(404, e.getMessage());
        } catch (RuntimeException e) {
            return R.error(503, e.getMessage());
        }
    }

    @GetMapping("/products")
    public R products() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", vipService.listProducts());
            return R.ok().put("data", data);
        } catch (RuntimeException e) {
            return R.error(503, e.getMessage());
        }
    }

    @PostMapping("/orders")
    public R createOrder(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestBody(required = false) CreateNaviVipOrderRequest request) {
        String phone = authenticatedPhone(authorization);
        if (phone == null) {
            return R.error(401, "登录状态已失效，请重新登录");
        }
        if (request == null) {
            return R.error(400, "Request body is required");
        }
        try {
            return R.ok().put("data", vipService.createOrder(phone, request.getProductId(), request.getAppid(), request.getPayChannel()));
        } catch (IllegalArgumentException e) {
            return R.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return R.error(503, e.getMessage());
        }
    }

    @GetMapping("/orders/{orderId}")
    public R order(@RequestHeader(value = "Authorization", required = false) String authorization,
                   @PathVariable("orderId") String orderId) {
        String phone = authenticatedPhone(authorization);
        if (phone == null) {
            return R.error(401, "登录状态已失效，请重新登录");
        }
        try {
            return R.ok().put("data", vipService.getOrder(phone, orderId));
        } catch (IllegalArgumentException e) {
            return R.error(404, e.getMessage());
        } catch (RuntimeException e) {
            return R.error(503, e.getMessage());
        }
    }

    /**
     * 微信支付异步回调，由微信服务器直接调用，不带 Authorization；
     * 通过 Wechatpay-* 头做签名校验来鉴权。
     */
    @PostMapping("/notify")
    public ResponseEntity<String> wechatNotify(@RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
                                                @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
                                                @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
                                                @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
                                                @RequestBody(required = false) String body) {
        try {
            if (serial == null || signature == null || timestamp == null || nonce == null || body == null
                    || !weChatPayClient.verifyNotifySignature(serial, timestamp, nonce, body, signature)) {
                log.warn("微信支付回调签名校验失败");
                return notifyResponse(500, "FAIL", "签名校验失败");
            }
            JSONObject root = JSON.parseObject(body);
            if (!"TRANSACTION.SUCCESS".equals(root.getString("event_type"))) {
                return notifyResponse(200, "SUCCESS", "成功");
            }
            JSONObject resource = root.getJSONObject("resource");
            String plain = weChatPayClient.decryptToString(resource.getString("associated_data"),
                    resource.getString("nonce"), resource.getString("ciphertext"));
            JSONObject decoded = JSON.parseObject(plain);
            if ("SUCCESS".equals(decoded.getString("trade_state"))) {
                vipService.confirmPaid(decoded.getString("out_trade_no"), decoded.getString("transaction_id"));
            }
            return notifyResponse(200, "SUCCESS", "成功");
        } catch (Exception e) {
            log.warn("处理微信支付回调失败: {}", e.getMessage());
            return notifyResponse(500, "FAIL", "处理失败");
        }
    }

    /**
     * 支付宝异步回调，表单方式提交，用支付宝公钥校验全部参数的签名后再处理。
     */
    @PostMapping("/alipay/notify")
    public ResponseEntity<String> alipayNotify(@RequestParam Map<String, String> params) {
        try {
            if (!alipayClient.verifyNotifySign(params)) {
                log.warn("支付宝回调签名校验失败");
                return ResponseEntity.ok("failure");
            }
            String tradeStatus = params.get("trade_status");
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                vipService.confirmPaid(params.get("out_trade_no"), params.get("trade_no"));
            }
            return ResponseEntity.ok("success");
        } catch (Exception e) {
            log.warn("处理支付宝回调失败: {}", e.getMessage());
            return ResponseEntity.ok("failure");
        }
    }

    private ResponseEntity<String> notifyResponse(int httpStatus, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(httpStatus).contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(body));
    }

    private String authenticatedPhone(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return tokenService.verifyAndGetPhone(authorization.substring(7).trim());
    }
}
