package com.bootdo.ai.dto;

/**
 * Apple 游客权益绑定请求。
 *
 * 使用专用 DTO 而不是 Map，避免通用 Web 日志通过 Map.toString()
 * 把 guestAccessToken 原文写入日志。
 */
public class AppleBindRequest {
    private String guestAccessToken;

    public String getGuestAccessToken() {
        return guestAccessToken;
    }

    public void setGuestAccessToken(String guestAccessToken) {
        this.guestAccessToken = guestAccessToken;
    }
}
