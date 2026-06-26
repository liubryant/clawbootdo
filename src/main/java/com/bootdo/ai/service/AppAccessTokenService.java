package com.bootdo.ai.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

@Service
public class AppAccessTokenService {
    @Value("${navi.auth.tokenSecret:}")
    private String tokenSecret;

    @Value("${navi.auth.tokenTtlSeconds:2592000}")
    private long tokenTtlSeconds;

    public String issue(String phone) {
        // Keep the existing login API usable when token authentication is not enabled.
        // The development profile configures a secret; production must opt in explicitly.
        if (StringUtils.isBlank(tokenSecret)) {
            return null;
        }
        requireSecret();
        long expiresAt = System.currentTimeMillis() / 1000L + tokenTtlSeconds;
        String payload = phone + "|" + expiresAt + "|" + UUID.randomUUID().toString().replace("-", "");
        return base64(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
    }

    public String verifyAndGetPhone(String token) {
        if (StringUtils.isBlank(token) || StringUtils.isBlank(tokenSecret)) {
            return null;
        }
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            String[] values = payload.split("\\|", 3);
            if (values.length != 3 || Long.parseLong(values[1]) < System.currentTimeMillis() / 1000L) {
                return null;
            }
            return values[0];
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return base64(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create app access token", e);
        }
    }

    private String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireSecret() {
        if (StringUtils.isBlank(tokenSecret) || tokenSecret.length() < 32) {
            throw new IllegalStateException("navi.auth.tokenSecret must contain at least 32 characters");
        }
    }
}
