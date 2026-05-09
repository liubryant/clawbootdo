package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 拉取远端 apiKey 信息（http://cjym123.cn/api/info）。
 *
 * 返回结构示例：
 * {"resultCode":200,"data":{"apiKey":"xxx"}}
 */
@Component
public class ApiInfoClient {

    private final AiProperties aiProperties;

    public ApiInfoClient(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * @return 远端 apiKey（非空、已 trim）
     */
    public String fetchApiKey() throws IOException {
        String url = safeTrim(aiProperties.getGlm().getApiInfoUrl());
        if (url.isEmpty()) {
            throw new IOException("ai.glm.apiInfoUrl is empty");
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(aiProperties.getGlm().getApiInfoConnectTimeoutMs());
        conn.setReadTimeout(aiProperties.getGlm().getApiInfoReadTimeoutMs());
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);
        conn.disconnect();

        if (code >= 400) {
            throw new IOException("apiInfo http error: HTTP " + code + ", body=" + body);
        }

        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (Exception ex) {
            throw new IOException("apiInfo invalid json: " + ex.getMessage() + ", body=" + body);
        }

        Integer resultCode = root == null ? null : root.getInteger("resultCode");
        if (resultCode == null || resultCode != 200) {
            throw new IOException("apiInfo bad resultCode: " + resultCode + ", body=" + body);
        }

        JSONObject data = root.getJSONObject("data");
        String apiKey = data == null ? null : data.getString("apiKey");
        apiKey = safeTrim(apiKey);
        if (apiKey.isEmpty()) {
            throw new IOException("apiInfo apiKey is empty");
        }
        return apiKey;
    }

    private String safeTrim(String v) {
        return v == null ? "" : v.trim();
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
