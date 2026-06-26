package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 微信支付 APIv3 客户端（APP 下单方式），不依赖第三方 SDK，避免引入新的依赖树
 * 与项目里已有的 mysql/druid/activiti/aliyun 等依赖产生版本冲突。签名/解密均按官方
 * APIv3 文档手工实现：请求签名 SHA256withRSA，回调内容解密 AEAD_AES_256_GCM。
 */
@Service
public class WeChatPayClient {
    private static final String API_HOST = "https://api.mch.weixin.qq.com";
    private static final long CERT_CACHE_TTL_MS = 3600_000L;

    @Value("${navi.wechat.appId:}")
    private String appId;
    @Value("${navi.wechat.merchantId:}")
    private String mchId;
    @Value("${navi.wechat.apiV3Key:}")
    private String apiV3Key;
    @Value("${navi.wechat.merchantSerialNo:}")
    private String merchantSerialNo;
    @Value("${navi.wechat.privateKeyPath:config/wechat/apiclient_key.pem}")
    private String privateKeyPath;
    @Value("${navi.wechat.notifyUrl:}")
    private String notifyUrl;

    private volatile PrivateKey merchantPrivateKey;
    private final Object privateKeyLock = new Object();

    private final Map<String, X509Certificate> platformCerts = new HashMap<>();
    private volatile long platformCertsFetchedAt;
    private final ReentrantLock certsLock = new ReentrantLock();

    public boolean isConfigured() {
        return !StringUtils.isBlank(appId) && !StringUtils.isBlank(mchId) && !StringUtils.isBlank(apiV3Key)
                && !StringUtils.isBlank(merchantSerialNo) && !StringUtils.isBlank(notifyUrl)
                && tryLoadPrivateKey();
    }

    private boolean tryLoadPrivateKey() {
        try {
            return privateKey() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static final class PrepayResult {
        public final String prepayId;

        public PrepayResult(String prepayId) {
            this.prepayId = prepayId;
        }
    }

    public PrepayResult createAppPrepay(String outTradeNo, String description, long totalFen) throws IOException {
        JSONObject body = new JSONObject(true);
        body.put("appid", appId);
        body.put("mchid", mchId);
        body.put("description", description);
        body.put("out_trade_no", outTradeNo);
        body.put("notify_url", notifyUrl);
        JSONObject amount = new JSONObject(true);
        amount.put("total", totalFen);
        amount.put("currency", "CNY");
        body.put("amount", amount);

        String responseBody = request("POST", "/v3/pay/transactions/app", body.toJSONString());
        JSONObject result = JSON.parseObject(responseBody);
        String prepayId = result == null ? null : result.getString("prepay_id");
        if (StringUtils.isBlank(prepayId)) {
            throw new IOException("微信下单未返回prepay_id: " + responseBody);
        }
        return new PrepayResult(prepayId);
    }

    public Map<String, String> buildAppPayParams(String prepayId) {
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonceStr = randomNonce();
        // APIv3 APP调起支付签名串固定格式：appId\ntimeStamp\nnonceStr\nprepayId\n
        // 官方文档明确第四行是裸prepayId值，不带"prepay_id="前缀（这点与JSAPI的package字段约定不同）
        String message = appId + "\n" + timeStamp + "\n" + nonceStr + "\n" + prepayId + "\n";
        String sign = rsaSign(message);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("appId", appId);
        params.put("partnerId", mchId);
        params.put("prepayId", prepayId);
        params.put("packageValue", "Sign=WXPay");
        params.put("nonceStr", nonceStr);
        params.put("timeStamp", timeStamp);
        params.put("sign", sign);
        // APIv3 RSA签名必须显式声明signType，否则微信SDK会按旧版MD5校验导致"签名验证失败"
        params.put("signType", "RSA");
        return params;
    }

    public static final class OrderQueryResult {
        public final String tradeState;
        public final String transactionId;

        public OrderQueryResult(String tradeState, String transactionId) {
            this.tradeState = tradeState;
            this.transactionId = transactionId;
        }
    }

    public OrderQueryResult queryByOutTradeNo(String outTradeNo) throws IOException {
        String path = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "?mchid=" + mchId;
        try {
            String responseBody = request("GET", path, "");
            JSONObject result = JSON.parseObject(responseBody);
            return new OrderQueryResult(result.getString("trade_state"), result.getString("transaction_id"));
        } catch (WeChatPayApiException e) {
            if ("ORDER_NOT_EXIST".equals(e.errorCode)) {
                return new OrderQueryResult("NOTPAY", null);
            }
            throw e;
        }
    }

    public boolean verifyNotifySignature(String serial, String timestamp, String nonce, String body, String signature) {
        try {
            PublicKey publicKey = platformPublicKey(serial);
            if (publicKey == null) {
                return false;
            }
            String message = timestamp + "\n" + nonce + "\n" + body + "\n";
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    public String decryptToString(String associatedData, String nonce, String ciphertextBase64) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("微信支付回调解密失败: " + e.getMessage(), e);
        }
    }

    private PublicKey platformPublicKey(String serial) throws IOException {
        X509Certificate cert = platformCerts.get(serial);
        if (cert == null || System.currentTimeMillis() - platformCertsFetchedAt > CERT_CACHE_TTL_MS) {
            refreshPlatformCerts();
            cert = platformCerts.get(serial);
        }
        return cert == null ? null : cert.getPublicKey();
    }

    private void refreshPlatformCerts() throws IOException {
        certsLock.lock();
        try {
            String responseBody = request("GET", "/v3/certificates", "");
            JSONObject result = JSON.parseObject(responseBody);
            JSONArray data = result == null ? null : result.getJSONArray("data");
            if (data == null) {
                return;
            }
            Map<String, X509Certificate> fresh = new HashMap<>();
            for (int i = 0; i < data.size(); i++) {
                JSONObject cert = data.getJSONObject(i);
                JSONObject enc = cert.getJSONObject("encrypt_certificate");
                String pem = decryptToString(enc.getString("associated_data"), enc.getString("nonce"), enc.getString("ciphertext"));
                X509Certificate x509 = parseCertificate(pem);
                fresh.put(cert.getString("serial_no"), x509);
            }
            platformCerts.putAll(fresh);
            platformCertsFetchedAt = System.currentTimeMillis();
        } finally {
            certsLock.unlock();
        }
    }

    private X509Certificate parseCertificate(String pem) throws IOException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (CertificateException e) {
            throw new IOException("解析微信支付平台证书失败: " + e.getMessage(), e);
        }
    }

    private String rsaSign(String message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("微信支付签名失败: " + e.getMessage(), e);
        }
    }

    private PrivateKey privateKey() {
        if (merchantPrivateKey == null) {
            synchronized (privateKeyLock) {
                if (merchantPrivateKey == null) {
                    merchantPrivateKey = loadPrivateKey(privateKeyPath);
                }
            }
        }
        return merchantPrivateKey;
    }

    private PrivateKey loadPrivateKey(String path) {
        try {
            String pem = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException("加载微信支付商户私钥失败(" + path + "): " + e.getMessage(), e);
        }
    }

    private String request(String method, String path, String body) throws IOException {
        String nonceStr = randomNonce();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String signMessage = method + "\n" + path + "\n" + timestamp + "\n" + nonceStr + "\n" + body + "\n";
        String signature = rsaSign(signMessage);
        String authorization = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchId + "\",nonce_str=\"" + nonceStr
                + "\",signature=\"" + signature + "\",timestamp=\"" + timestamp + "\",serial_no=\"" + merchantSerialNo + "\"";

        HttpURLConnection conn = (HttpURLConnection) new URL(API_HOST + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", authorization);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "navi-vip-pay/1.0");
        if (!body.isEmpty()) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String responseBody = readAll(stream);
        conn.disconnect();
        if (code >= 400) {
            String errorCode = null;
            try {
                errorCode = JSON.parseObject(responseBody).getString("code");
            } catch (Exception ignored) {
            }
            throw new WeChatPayApiException(code, errorCode, responseBody);
        }
        return responseBody;
    }

    private String randomNonce() {
        return UUID.randomUUID().toString().replace("-", "");
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

    public static final class WeChatPayApiException extends IOException {
        public final int httpStatus;
        public final String errorCode;

        public WeChatPayApiException(int httpStatus, String errorCode, String body) {
            super("微信支付接口错误 HTTP" + httpStatus + " code=" + errorCode + " body=" + body);
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }
    }
}
