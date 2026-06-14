package com.bootdo.ai.service.impl;

import com.alibaba.fastjson.JSON;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.bootdo.ai.config.SmsProperties;
import com.bootdo.ai.service.SmsCodeService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class AliyunSmsCodeServiceImpl implements SmsCodeService {
    private static final Pattern MAINLAND_PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final String CODE_PREFIX = "sms:login:code:";
    private static final String LIMIT_PREFIX = "sms:login:limit:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsProperties smsProperties;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, ExpiringValue> localCodeCache = new ConcurrentHashMap<String, ExpiringValue>();
    private final Map<String, ExpiringValue> localLimitCache = new ConcurrentHashMap<String, ExpiringValue>();
    private volatile com.aliyun.dysmsapi20170525.Client client;

    public AliyunSmsCodeServiceImpl(SmsProperties smsProperties, StringRedisTemplate redisTemplate) {
        this.smsProperties = smsProperties;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public int sendLoginCode(String phone) {
        String normalizedPhone = normalizePhone(phone);
        if (!MAINLAND_PHONE.matcher(normalizedPhone).matches()) {
            throw new IllegalArgumentException("Invalid phone number");
        }
        if (!smsProperties.isEnabled()) {
            throw new IllegalStateException("SMS service is disabled");
        }
        if (StringUtils.isBlank(smsProperties.getSignName())
                || StringUtils.isBlank(smsProperties.getTemplateCode())) {
            throw new IllegalStateException("SMS signName or templateCode is not configured");
        }

        String limitKey = LIMIT_PREFIX + normalizedPhone;
        if (isSendLimited(limitKey)) {
            throw new IllegalStateException("SMS code requested too frequently");
        }

        String code = generateCode();
        sendSms(normalizedPhone, code);

        int expireSeconds = Math.max(60, smsProperties.getCodeExpireSeconds());
        int intervalSeconds = Math.max(30, smsProperties.getSendIntervalSeconds());
        storeCode(CODE_PREFIX + normalizedPhone, code, expireSeconds);
        storeLimit(limitKey, intervalSeconds);
        return expireSeconds;
    }

    @Override
    public boolean verifyLoginCode(String phone, String code) {
        if (StringUtils.isBlank(code)) {
            return false;
        }
        String normalizedPhone = normalizePhone(phone);
        String codeKey = CODE_PREFIX + normalizedPhone;
        String savedCode = getCode(codeKey);
        if (savedCode == null || !savedCode.equals(code.trim())) {
            return false;
        }
        deleteCode(codeKey);
        return true;
    }

    private String getCode(String codeKey) {
        try {
            return redisTemplate.opsForValue().get(codeKey);
        } catch (RuntimeException ex) {
            ExpiringValue value = localCodeCache.get(codeKey);
            if (value == null || value.isExpired()) {
                return null;
            }
            return value.value;
        }
    }

    private void deleteCode(String codeKey) {
        try {
            redisTemplate.delete(codeKey);
        } catch (RuntimeException ex) {
            localCodeCache.remove(codeKey);
        }
    }

    private boolean isSendLimited(String limitKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(limitKey));
        } catch (RuntimeException ex) {
            return isLocalActive(localLimitCache, limitKey);
        }
    }

    private void storeCode(String codeKey, String code, int expireSeconds) {
        try {
            redisTemplate.opsForValue().set(codeKey, code, expireSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            localCodeCache.put(codeKey, ExpiringValue.of(code, expireSeconds));
        }
    }

    private void storeLimit(String limitKey, int intervalSeconds) {
        try {
            redisTemplate.opsForValue().set(limitKey, "1", intervalSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            localLimitCache.put(limitKey, ExpiringValue.of("1", intervalSeconds));
        }
    }

    private boolean isLocalActive(Map<String, ExpiringValue> cache, String key) {
        ExpiringValue value = cache.get(key);
        if (value == null) {
            return false;
        }
        if (value.isExpired()) {
            cache.remove(key);
            return false;
        }
        return true;
    }

    private void sendSms(String phone, String code) {
        SendSmsRequest request = new SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(smsProperties.getSignName())
                .setTemplateCode(smsProperties.getTemplateCode())
                .setTemplateParam(JSON.toJSONString(Collections.singletonMap("code", code)));
        try {
            SendSmsResponse response = getClient().sendSmsWithOptions(request, new RuntimeOptions());
            String responseCode = response == null || response.getBody() == null
                    ? null
                    : response.getBody().getCode();
            if (!"OK".equalsIgnoreCase(responseCode)) {
                String message = response == null || response.getBody() == null
                        ? "SMS send failed"
                        : response.getBody().getMessage();
                throw new IllegalStateException(message);
            }
        } catch (TeaException error) {
            throw new IllegalStateException(error.getMessage(), error);
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private com.aliyun.dysmsapi20170525.Client getClient() throws Exception {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    com.aliyun.credentials.Client credential = new com.aliyun.credentials.Client();
                    Config config = new Config().setCredential(credential);
                    config.endpoint = StringUtils.defaultIfBlank(
                            smsProperties.getEndpoint(),
                            "dysmsapi.aliyuncs.com");
                    client = new com.aliyun.dysmsapi20170525.Client(config);
                }
            }
        }
        return client;
    }

    private String normalizePhone(String phone) {
        String safe = phone == null ? "" : phone.trim();
        safe = safe.replace(" ", "").replace("-", "");
        if (safe.startsWith("+86")) {
            safe = safe.substring(3);
        } else if (safe.startsWith("86") && safe.length() == 13) {
            safe = safe.substring(2);
        }
        return safe;
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1000000));
    }

    private static class ExpiringValue {
        private final String value;
        private final long expireAt;

        private ExpiringValue(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        private static ExpiringValue of(String value, int ttlSeconds) {
            return new ExpiringValue(value, System.currentTimeMillis() + ttlSeconds * 1000L);
        }

        private boolean isExpired() {
            return System.currentTimeMillis() >= expireAt;
        }
    }
}
