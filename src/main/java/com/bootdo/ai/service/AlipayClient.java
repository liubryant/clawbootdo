package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝开放平台客户端（APP支付方式），不依赖官方SDK，跟WeChatPayClient风格保持一致。
 * APP支付下单不需要请求支付宝网关：服务端本地用商户私钥把整笔请求签好，
 * 直接把这个完整字符串交给客户端，客户端原样传给支付宝SDK的 PayTask.payV2() 即可。
 */
@Service
public class AlipayClient {
    private static final String GATEWAY_URL = "https://openapi.alipay.com/gateway.do";

    @Value("${navi.alipay.appId:}")
    private String appId;
    @Value("${navi.alipay.privateKeyPath:config/alipay/app_private_key.pem}")
    private String privateKeyPath;
    @Value("${navi.alipay.publicKeyPath:config/alipay/alipay_public_key.pem}")
    private String publicKeyPath;
    @Value("${navi.alipay.notifyUrl:}")
    private String notifyUrl;

    private volatile PrivateKey appPrivateKey;
    private volatile PublicKey alipayPublicKey;
    private final Object keyLock = new Object();

    public boolean isConfigured() {
        return !StringUtils.isBlank(appId) && !StringUtils.isBlank(notifyUrl) && tryLoadKeys();
    }

    private boolean tryLoadKeys() {
        try {
            return privateKey() != null && publicKey() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建APP支付完整请求字符串，直接交给客户端 PayTask.payV2() 使用。
     */
    public String buildAppPayOrderString(String outTradeNo, String subject, String totalAmountYuan) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_id", appId);
        params.put("method", "alipay.trade.app.pay");
        params.put("format", "JSON");
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        params.put("version", "1.0");
        params.put("notify_url", notifyUrl);

        JSONObject bizContent = new JSONObject(true);
        bizContent.put("subject", subject);
        bizContent.put("out_trade_no", outTradeNo);
        bizContent.put("total_amount", totalAmountYuan);
        bizContent.put("product_code", "QUICK_MSECURITY_PAY");
        bizContent.put("timeout_express", "30m");
        params.put("biz_content", bizContent.toJSONString());

        String sign = sign(buildSignSource(params));
        return buildQueryString(params, sign);
    }

    public static final class TradeQueryResult {
        public final String tradeStatus;
        public final String tradeNo;

        public TradeQueryResult(String tradeStatus, String tradeNo) {
            this.tradeStatus = tradeStatus;
            this.tradeNo = tradeNo;
        }
    }

    public TradeQueryResult queryTrade(String outTradeNo) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_id", appId);
        params.put("method", "alipay.trade.query");
        params.put("format", "JSON");
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        params.put("version", "1.0");
        JSONObject bizContent = new JSONObject(true);
        bizContent.put("out_trade_no", outTradeNo);
        params.put("biz_content", bizContent.toJSONString());

        String sign = sign(buildSignSource(params));
        String body = buildQueryString(params, sign);

        String responseBody = post(body);
        JSONObject root = JSON.parseObject(responseBody);
        JSONObject response = root == null ? null : root.getJSONObject("alipay_trade_query_response");
        if (response == null) {
            throw new IOException("支付宝查单响应格式异常: " + responseBody);
        }
        String code = response.getString("code");
        if (!"10000".equals(code)) {
            if ("40004".equals(code) || "40006".equals(code)) {
                return new TradeQueryResult("NOTPAY", null);
            }
            throw new IOException("支付宝查单失败 code=" + code + " msg=" + response.getString("sub_msg"));
        }
        return new TradeQueryResult(response.getString("trade_status"), response.getString("trade_no"));
    }

    /**
     * 验证异步通知签名：对除 sign/sign_type 外的所有参数按key排序后拼接，
     * 用支付宝公钥校验。
     */
    public boolean verifyNotifySign(Map<String, String> params) {
        try {
            String sign = params.get("sign");
            if (StringUtils.isBlank(sign)) {
                return false;
            }
            TreeMap<String, String> toVerify = new TreeMap<>(params);
            toVerify.remove("sign");
            toVerify.remove("sign_type");
            String source = buildSignSource(toVerify);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey());
            verifier.update(source.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            return false;
        }
    }

    private String buildSignSource(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (StringUtils.isBlank(entry.getValue())) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    private String buildQueryString(Map<String, String> params, String sign) {
        try {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (StringUtils.isBlank(entry.getValue())) {
                    continue;
                }
                if (!first) {
                    sb.append('&');
                }
                sb.append(entry.getKey()).append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                first = false;
            }
            sb.append("&sign=").append(URLEncoder.encode(sign, "UTF-8"));
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sign(String source) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update(source.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("支付宝签名失败: " + e.getMessage(), e);
        }
    }

    private PrivateKey privateKey() {
        if (appPrivateKey == null) {
            synchronized (keyLock) {
                if (appPrivateKey == null) {
                    appPrivateKey = loadPrivateKey(privateKeyPath);
                }
            }
        }
        return appPrivateKey;
    }

    private PublicKey publicKey() {
        if (alipayPublicKey == null) {
            synchronized (keyLock) {
                if (alipayPublicKey == null) {
                    alipayPublicKey = loadPublicKey(publicKeyPath);
                }
            }
        }
        return alipayPublicKey;
    }

    private PrivateKey loadPrivateKey(String path) {
        try {
            String base64 = stripPem(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException("加载支付宝应用私钥失败(" + path + "): " + e.getMessage(), e);
        }
    }

    private PublicKey loadPublicKey(String path) {
        try {
            String base64 = stripPem(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException("加载支付宝公钥失败(" + path + "): " + e.getMessage(), e);
        }
    }

    private String stripPem(String pem) {
        return pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    private String post(String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(GATEWAY_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String responseBody = readAll(stream);
        conn.disconnect();
        if (code >= 400) {
            throw new IOException("支付宝接口HTTP错误 " + code + ": " + responseBody);
        }
        return responseBody;
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
