package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 苹果内购(StoreKit 2)交易凭证(JWS)本地校验器。
 *
 * StoreKit 2 每笔交易由 Apple 用其证书链签名(ES256)，客户端拿到的是一段
 * JWS(header.payload.signature)。这里在本地完成：
 *   1) 用内置的 x5c 证书链验签，并校验证书链根为 Apple Root CA G3(离线，不需请求苹果)；
 *   2) 校验 JWS 签名(ES256)确保 payload 未被篡改。
 * 沙盒与正式环境的交易都由同一条 Apple 证书链签名，因此无需区分 verifyReceipt 沙盒/正式地址。
 *
 * 该类完全使用 JDK 内置能力 + fastjson，不引入任何新依赖。
 */
@Component
public class AppleIapVerifier {
    private static final Logger log = LoggerFactory.getLogger(AppleIapVerifier.class);

    private volatile X509Certificate appleRoot;

    /** 校验通过后返回的交易 payload 关键字段。 */
    public static class VerifiedTransaction {
        public String transactionId;
        public String originalTransactionId;
        public String bundleId;
        public String productId;
        public String environment;
        public Long purchaseDate;
        public Long expiresDate;
        public boolean revoked;
    }

    /**
     * 校验一段 StoreKit 2 JWS 交易凭证。
     * @throws SecurityException 任意校验环节失败
     */
    public VerifiedTransaction verify(String jws) {
        if (jws == null || jws.isEmpty()) {
            throw new SecurityException("JWS 为空");
        }
        String[] parts = jws.split("\\.");
        if (parts.length != 3) {
            throw new SecurityException("JWS 格式非法");
        }
        try {
            Base64.Decoder urlDecoder = Base64.getUrlDecoder();
            String headerJson = new String(urlDecoder.decode(parts[0]), StandardCharsets.UTF_8);
            String payloadJson = new String(urlDecoder.decode(parts[1]), StandardCharsets.UTF_8);
            byte[] signature = urlDecoder.decode(parts[2]);

            JSONObject header = JSON.parseObject(headerJson);
            if (!"ES256".equals(header.getString("alg"))) {
                throw new SecurityException("不支持的签名算法: " + header.getString("alg"));
            }
            JSONArray x5c = header.getJSONArray("x5c");
            if (x5c == null || x5c.size() < 2) {
                throw new SecurityException("x5c 证书链缺失");
            }

            // 1) 解析证书链并校验其根为 Apple Root CA G3
            List<X509Certificate> chain = parseChain(x5c);
            validateChainToAppleRoot(chain);

            // 2) 用叶子证书公钥验签 JWS
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(chain.get(0).getPublicKey());
            verifier.update(signingInput);
            if (!verifier.verify(joseToDer(signature))) {
                throw new SecurityException("JWS 签名校验失败");
            }

            // 3) 解析业务字段
            JSONObject payload = JSON.parseObject(payloadJson);
            VerifiedTransaction tx = new VerifiedTransaction();
            tx.transactionId = payload.getString("transactionId");
            tx.originalTransactionId = payload.getString("originalTransactionId");
            tx.bundleId = payload.getString("bundleId");
            tx.productId = payload.getString("productId");
            tx.environment = payload.getString("environment");
            tx.purchaseDate = payload.getLong("purchaseDate");
            tx.expiresDate = payload.getLong("expiresDate");
            tx.revoked = payload.get("revocationDate") != null;
            if (tx.transactionId == null || tx.productId == null || tx.bundleId == null) {
                throw new SecurityException("JWS payload 缺少必要字段");
            }
            return tx;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            log.warn("苹果内购凭证校验异常: {}", e.getMessage());
            throw new SecurityException("凭证校验失败");
        }
    }

    private List<X509Certificate> parseChain(JSONArray x5c) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        Base64.Decoder std = Base64.getDecoder();
        for (int i = 0; i < x5c.size(); i++) {
            byte[] der = std.decode(x5c.getString(i));
            chain.add((X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der)));
        }
        return chain;
    }

    /**
     * 校验证书链 [leaf, intermediate(, root)] 能够信任到 Apple Root CA G3。
     * 信任锚为本地内置的 Apple Root CA G3，因此即便 x5c 里带了根证书也不会被冒用。
     */
    private void validateChainToAppleRoot(List<X509Certificate> chain) throws Exception {
        X509Certificate root = loadAppleRoot();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        // CertPath 不包含信任锚(根证书)，仅 [leaf, intermediate]
        List<X509Certificate> path = new ArrayList<>();
        for (X509Certificate c : chain) {
            if (!c.equals(root)) {
                path.add(c);
            }
        }
        CertPath certPath = cf.generateCertPath(path);
        Set<TrustAnchor> anchors = new HashSet<>();
        anchors.add(new TrustAnchor(root, null));
        PKIXParameters params = new PKIXParameters(anchors);
        params.setRevocationEnabled(false);
        CertPathValidator validator = CertPathValidator.getInstance("PKIX");
        validator.validate(certPath, params);
    }

    private X509Certificate loadAppleRoot() throws Exception {
        X509Certificate cached = appleRoot;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (appleRoot == null) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                try (InputStream in = new ClassPathResource("apple/AppleRootCA-G3.cer").getInputStream()) {
                    appleRoot = (X509Certificate) cf.generateCertificate(in);
                }
            }
            return appleRoot;
        }
    }

    /**
     * 把 JOSE 的 ECDSA 签名(raw r||s, 各 32 字节)转换成 JDK 需要的 DER 编码。
     * Java 8 的 SHA256withECDSA 只接受 DER 签名，没有 inP1363Format 变体。
     */
    private byte[] joseToDer(byte[] jose) {
        int n = jose.length / 2;
        byte[] rBytes = new byte[n];
        byte[] sBytes = new byte[n];
        System.arraycopy(jose, 0, rBytes, 0, n);
        System.arraycopy(jose, n, sBytes, 0, n);
        BigInteger r = new BigInteger(1, rBytes);
        BigInteger s = new BigInteger(1, sBytes);
        byte[] rEnc = r.toByteArray();
        byte[] sEnc = s.toByteArray();
        int len = 2 + rEnc.length + 2 + sEnc.length;
        List<Byte> out = new ArrayList<>();
        out.add((byte) 0x30);
        if (len >= 128) {
            out.add((byte) 0x81);
            out.add((byte) len);
        } else {
            out.add((byte) len);
        }
        out.add((byte) 0x02);
        out.add((byte) rEnc.length);
        for (byte b : rEnc) out.add(b);
        out.add((byte) 0x02);
        out.add((byte) sEnc.length);
        for (byte b : sEnc) out.add(b);
        byte[] der = new byte[out.size()];
        for (int i = 0; i < der.length; i++) der[i] = out.get(i);
        return der;
    }
}
