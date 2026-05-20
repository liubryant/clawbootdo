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
 * 拉取远端聊天上游配置（http://cjym123.cn/api/info）。
 *
 * 返回结构示例：
 * {"resultCode":200,"data":{"apiKey":"xxx"}}
 * {"resultCode":200,"data":{"aiProvider":"siliconflow","aiBaseUrl":"https://api.siliconflow.cn/v1","aiApiKey":"sk-xxx","aiModel":"Qwen/Qwen2.5-7B-Instruct"}}
 */
@Component
public class ApiInfoClient {

    private final AiProperties aiProperties;

    public ApiInfoClient(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public DynamicChatUpstreamConfig fetchChatUpstreamConfig() throws IOException {
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
        String provider = firstNonBlank(data, "aiProvider", "provider");
        String baseUrl = firstNonBlank(data, "aiBaseUrl", "baseUrl");
        String apiKey = firstNonBlank(data, "aiApiKey", "apiKey");
        String model = firstNonBlank(data, "aiModel", "model");

        provider = safeTrim(provider);
        baseUrl = resolveBaseUrl(provider, baseUrl);
        apiKey = safeTrim(apiKey);
        model = resolveModel(provider, model);

        if (apiKey.isEmpty()) {
            throw new IOException("apiInfo apiKey is empty");
        }
        return new DynamicChatUpstreamConfig(provider, baseUrl, apiKey, model);
    }

    private String safeTrim(String v) {
        return v == null ? "" : v.trim();
    }

    private String firstNonBlank(JSONObject data, String... keys) {
        if (data == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = safeTrim(data.getString(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String resolveBaseUrl(String provider, String baseUrl) {
        String normalizedProvider = safeTrim(provider).toLowerCase();
        String normalizedBaseUrl = safeTrim(baseUrl);
        if (!normalizedBaseUrl.isEmpty()) {
            return normalizedBaseUrl;
        }
        if ("siliconflow".equals(normalizedProvider) || "qwen".equals(normalizedProvider)) {
            return "https://api.siliconflow.cn/v1";
        }
        return "";
    }

    private String resolveModel(String provider, String model) {
        String normalizedModel = safeTrim(model);
        if (!normalizedModel.isEmpty()) {
            return normalizedModel;
        }
        String normalizedProvider = safeTrim(provider).toLowerCase();
        if ("siliconflow".equals(normalizedProvider) || "qwen".equals(normalizedProvider)) {
            return "Qwen/Qwen2.5-7B-Instruct";
        }
        return "";
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
