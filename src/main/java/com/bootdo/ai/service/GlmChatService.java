package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.config.AiProperties;
import com.bootdo.ai.dto.ChatCompletionRequest;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
public class GlmChatService {

    private final AiProperties aiProperties;

    public GlmChatService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public void streamCompletion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        HttpURLConnection conn = openConnection(buildUpstreamPayload(request, true), true);
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();

        if (code >= 400) {
            String err = readAll(stream);
            response.setStatus(502);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(errorJson("upstream_error", "GLM upstream error: HTTP " + code, err));
            return;
        }

        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
             PrintWriter writer = response.getWriter()) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    writer.write("data: [DONE]\n\n");
                    writer.flush();
                    break;
                }

                String compat = convertToOpenAiDelta(data);
                if (compat == null || compat.trim().isEmpty()) {
                    // 过滤仅包含 reasoning_content 的分片，避免前端收到大量“思维链 token”
                    continue;
                }
                writer.write("data: " + compat + "\n\n");
                writer.flush();
            }
        } finally {
            conn.disconnect();
        }
    }

    public void nonStreamCompletion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        HttpURLConnection conn = openConnection(buildUpstreamPayload(request, false), false);
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);

        response.setStatus(code >= 400 ? 502 : 200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        String compatBody = body;
        if (code < 400) {
            compatBody = convertToOpenAiResponse(body);
        }
        response.getWriter().write(compatBody == null ? "{}" : compatBody);
        conn.disconnect();
    }

    private HttpURLConnection openConnection(String payload, boolean stream) throws IOException {
        String apiKey = aiProperties.getGlm().getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("GLM apiKey is empty, please set ai.glm.apiKey or GLM_API_KEY");
        }

        String base = aiProperties.getGlm().getBaseUrl();
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(aiProperties.getConnectTimeoutMs());
        conn.setReadTimeout(aiProperties.getReadTimeoutMs());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return conn;
    }

    private String buildUpstreamPayload(ChatCompletionRequest request, boolean stream) {
        JSONObject root = new JSONObject();
        String model = resolveModelForUpstream(request.getModel());
        root.put("model", model);
        root.put("stream", stream);
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }

        JSONArray messages = new JSONArray();
        if (request.getMessages() != null) {
            for (ChatCompletionRequest.Message msg : request.getMessages()) {
                JSONObject m = new JSONObject();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                messages.add(m);
            }
        }
        root.put("messages", messages);
        return root.toJSONString();
    }

    private String convertToOpenAiDelta(String data) {
        try {
            JSONObject obj = JSON.parseObject(data);
            JSONArray choices = obj.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject first = choices.getJSONObject(0);
                JSONObject delta = first.getJSONObject("delta");
                if (delta != null) {
                    String content = delta.getString("content");
                    if (content != null && !content.isEmpty()) {
                        // 统一输出最小 OpenAI 兼容分片，避免把 reasoning_content 等字段透传给客户端
                        return buildCompatDelta(content);
                    }
                }

                JSONObject message = first.getJSONObject("message");
                if (message != null) {
                    String content = message.getString("content");
                    if (content != null && !content.isEmpty()) {
                        return buildCompatDelta(content);
                    }
                }
            }
        } catch (Exception ignore) {
            // 解析失败时跳过该分片，保证下游只消费标准 delta.content
        }
        return null;
    }

    private String resolveModelForUpstream(String requestModel) {
        String defaultModel = aiProperties.getGlm().getModel();
        String model = requestModel == null ? "" : requestModel.trim();
        if (model.isEmpty()) {
            return defaultModel;
        }
        // 兼容 INMOClawX 默认模型别名：openclaw:main
        if ("openclaw".equalsIgnoreCase(model) || model.toLowerCase().startsWith("openclaw:")) {
            return defaultModel;
        }
        return model;
    }

    private String convertToOpenAiResponse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return body;
        }
        try {
            JSONObject root = JSON.parseObject(body);
            JSONArray choices = root.getJSONArray("choices");
            if (choices != null) {
                for (int i = 0; i < choices.size(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    if (choice == null) {
                        continue;
                    }
                    JSONObject message = choice.getJSONObject("message");
                    if (message != null && message.containsKey("reasoning_content")) {
                        message.remove("reasoning_content");
                    }
                }
            }
            return root.toJSONString();
        } catch (Exception ignore) {
            return body;
        }
    }

    private String buildCompatDelta(String content) {
        JSONObject root = new JSONObject();
        JSONArray choices = new JSONArray();
        JSONObject first = new JSONObject();
        JSONObject delta = new JSONObject();
        delta.put("content", content);
        first.put("delta", delta);
        choices.add(first);
        root.put("choices", choices);
        return root.toJSONString();
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private String errorJson(String code, String message, String detail) {
        JSONObject err = new JSONObject();
        err.put("code", code);
        err.put("message", message);
        err.put("detail", detail);
        JSONObject root = new JSONObject();
        root.put("error", err);
        return root.toJSONString();
    }
}
