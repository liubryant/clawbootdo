package com.bootdo.ai.service;

public interface SmsCodeService {
    int sendLoginCode(String phone);

    /**
     * 校验短信验证码，校验成功后验证码即失效（一次性使用）。
     */
    boolean verifyLoginCode(String phone, String code);
}
