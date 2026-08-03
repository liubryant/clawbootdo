package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.dao.AppModelConfigDao;
import com.bootdo.ai.domain.AppModelConfigDO;
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
public class InspirePlanetChatService {
    private static final String APP_CODE = "inspireplanet";
    private static final String SYSTEM_PROMPT = "你是灵感星球的AI数字人助手，请使用自然、简洁的中文回答。";
    private final AppModelConfigDao configDao;

    public InspirePlanetChatService(AppModelConfigDao configDao) { this.configDao = configDao; }

    public void completion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        AppModelConfigDO config = requireConfig();
        boolean stream = request.getStream() == null || request.getStream();
        HttpURLConnection connection = open(config, payload(request, config, stream));
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (status >= 400) {
            writeJson(response, 502, upstreamError(status, readAll(input)));
            connection.disconnect();
            return;
        }
        if (stream) forwardStream(connection, input, response);
        else forwardJson(connection, input, response);
    }

    private AppModelConfigDO requireConfig() {
        AppModelConfigDO config = configDao.get(APP_CODE, "TEXT");
        if (config == null || config.getEnabled() == null || config.getEnabled() != 1)
            throw new IllegalStateException("灵感星球模型服务未启用");
        if (blank(config.getAiApiKey())) throw new IllegalStateException("请先在灵感星球模型管理中配置 API Key");
        if (blank(config.getAiBaseUrl()) || blank(config.getAiModel()))
            throw new IllegalStateException("灵感星球模型配置不完整");
        return config;
    }

    private JSONObject payload(ChatCompletionRequest request, AppModelConfigDO config, boolean stream) {
        JSONObject body = new JSONObject(true);
        body.put("model", config.getAiModel());
        JSONArray messages = new JSONArray();
        boolean hasSystem = request.getMessages() != null && !request.getMessages().isEmpty()
                && "system".equalsIgnoreCase(request.getMessages().get(0).getRole());
        if (!hasSystem) {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", SYSTEM_PROMPT);
            messages.add(system);
        }
        if (request.getMessages() != null) messages.addAll((JSONArray) JSON.toJSON(request.getMessages()));
        body.put("messages", messages);
        body.put("stream", stream);
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTools() != null && !request.getTools().isEmpty()) body.put("tools", request.getTools());
        if (request.getToolChoice() != null) body.put("tool_choice", request.getToolChoice());
        return body;
    }

    private HttpURLConnection open(AppModelConfigDO config, JSONObject payload) throws IOException {
        String base = config.getAiBaseUrl().replaceAll("/+$", "");
        String endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        if (!endpoint.startsWith("https://")) throw new IllegalStateException("灵感星球接口地址必须使用 HTTPS");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "text/event-stream, application/json");
        connection.setRequestProperty("Authorization", "Bearer " + config.getAiApiKey());
        byte[] bytes = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        return connection;
    }

    private void forwardStream(HttpURLConnection connection, InputStream input, HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             PrintWriter writer = response.getWriter()) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.write("\n");
                if (line.isEmpty()) writer.flush();
            }
            writer.flush();
        } finally { connection.disconnect(); }
    }

    private void forwardJson(HttpURLConnection connection, InputStream input, HttpServletResponse response) throws IOException {
        String body = readAll(input);
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
        connection.disconnect();
    }

    private JSONObject upstreamError(int status, String detail) {
        JSONObject error = new JSONObject();
        error.put("code", "upstream_error");
        error.put("message", "灵感星球模型请求失败：HTTP " + status);
        error.put("detail", detail);
        JSONObject root = new JSONObject();
        root.put("error", error);
        return root;
    }

    private void writeJson(HttpServletResponse response, int status, JSONObject body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body.toJSONString());
    }

    private String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
