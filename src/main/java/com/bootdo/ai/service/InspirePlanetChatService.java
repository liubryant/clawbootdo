package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.dao.AppModelConfigDao;
import com.bootdo.ai.domain.AppModelConfigDO;
import com.bootdo.ai.dto.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(InspirePlanetChatService.class);
    private static final String APP_CODE = "inspireplanet";
    private static final String SYSTEM_PROMPT = "你是灵感星球的AI数字人助手，请使用自然、简洁的中文回答。";
    private final AppModelConfigDao configDao;

    public InspirePlanetChatService(AppModelConfigDao configDao) { this.configDao = configDao; }

    public void completion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        AppModelConfigDO config = requireConfig();
        boolean stream = request.getStream() == null || request.getStream();
        HttpURLConnection connection = openResponses(config, responsesPayload(request, config, stream));
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (status >= 400) {
            String detail = readAll(input);
            connection.disconnect();
            if (requiresWebSearch(request)) {
                LOGGER.warn("InspirePlanet required web search failed, upstream status={}", status);
                writeJson(response, 502, upstreamError(status, detail));
                return;
            }
            // 联网插件、Responses API 或当前模型暂时不可用时，降级为原有 Chat API，
            // 避免影响灵感星球既有对话。该降级仅存在于灵感星球专用服务中。
            completionWithChatFallback(request, config, stream, response, status, detail);
            return;
        }
        if (stream) forwardResponsesStream(connection, input, response);
        else forwardResponsesJson(connection, input, response);
    }

    private void completionWithChatFallback(ChatCompletionRequest request, AppModelConfigDO config,
                                            boolean stream, HttpServletResponse response,
                                            int responsesStatus, String responsesDetail) throws IOException {
        HttpURLConnection connection = openChat(config, chatPayload(request, config, stream));
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (status >= 400) {
            String chatDetail = readAll(input);
            connection.disconnect();
            writeJson(response, 502, upstreamError(status,
                    "Responses API HTTP " + responsesStatus + ": " + responsesDetail
                            + "; Chat fallback HTTP " + status + ": " + chatDetail));
            return;
        }
        if (stream) forwardChatStream(connection, input, response);
        else forwardChatJson(connection, input, response);
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

    private JSONObject responsesPayload(ChatCompletionRequest request, AppModelConfigDO config, boolean stream) {
        JSONObject body = new JSONObject(true);
        body.put("model", config.getAiModel());
        JSONArray input = responsesMessages(request);
        body.put("input", input);
        body.put("stream", stream);
        JSONArray tools = new JSONArray();
        JSONObject webSearch = new JSONObject(true);
        webSearch.put("type", "web_search");
        tools.add(webSearch);
        body.put("tools", tools);
        if (requiresWebSearch(request)) body.put("tool_choice", "required");
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        return body;
    }

    private boolean requiresWebSearch(ChatCompletionRequest request) {
        if (request.getMessages() == null) return false;
        String latestUserMessage = null;
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message message = request.getMessages().get(i);
            if (message != null && "user".equalsIgnoreCase(message.getRole())) {
                Object content = message.getContent();
                latestUserMessage = content == null ? null : String.valueOf(content);
                break;
            }
        }
        if (blank(latestUserMessage)) return false;
        String[] realtimeKeywords = {
                "天气", "气温", "温度", "降雨", "下雨", "台风", "空气质量", "紫外线",
                "新闻", "热搜", "热点", "最新", "今天", "今日", "现在", "当前", "刚刚",
                "价格", "股价", "汇率", "油价", "金价", "比分", "赛程", "票房",
                "联网", "上网查", "搜索一下", "查一下"
        };
        for (String keyword : realtimeKeywords) {
            if (latestUserMessage.contains(keyword)) return true;
        }
        return false;
    }

    private JSONObject chatPayload(ChatCompletionRequest request, AppModelConfigDO config, boolean stream) {
        JSONObject body = new JSONObject(true);
        body.put("model", config.getAiModel());
        body.put("messages", messages(request));
        body.put("stream", stream);
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTools() != null && !request.getTools().isEmpty()) body.put("tools", request.getTools());
        if (request.getToolChoice() != null) body.put("tool_choice", request.getToolChoice());
        return body;
    }

    private JSONArray responsesMessages(ChatCompletionRequest request) {
        JSONArray messages = new JSONArray();
        boolean hasSystem = request.getMessages() != null && !request.getMessages().isEmpty()
                && "system".equalsIgnoreCase(request.getMessages().get(0).getRole());
        if (!hasSystem) {
            JSONObject system = new JSONObject(true);
            system.put("role", "system");
            system.put("content", SYSTEM_PROMPT);
            messages.add(system);
        }
        if (request.getMessages() == null) return messages;
        for (ChatCompletionRequest.Message source : request.getMessages()) {
            if (source == null || blank(source.getRole()) || source.getContent() == null) continue;
            JSONObject message = new JSONObject(true);
            message.put("role", source.getRole());
            message.put("content", source.getContent());
            messages.add(message);
        }
        return messages;
    }

    private JSONArray messages(ChatCompletionRequest request) {
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
        return messages;
    }

    private HttpURLConnection openResponses(AppModelConfigDO config, JSONObject payload) throws IOException {
        return open(config, "/responses", payload);
    }

    private HttpURLConnection openChat(AppModelConfigDO config, JSONObject payload) throws IOException {
        return open(config, "/chat/completions", payload);
    }

    private HttpURLConnection open(AppModelConfigDO config, String path, JSONObject payload) throws IOException {
        String base = config.getAiBaseUrl().replaceAll("/+$", "");
        String endpoint;
        if (base.endsWith("/chat/completions")) base = base.substring(0, base.length() - "/chat/completions".length());
        if (base.endsWith("/responses")) base = base.substring(0, base.length() - "/responses".length());
        endpoint = base + path;
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

    private void forwardResponsesStream(HttpURLConnection connection, InputStream input,
                                        HttpServletResponse response) throws IOException {
        prepareSse(response);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             PrintWriter writer = response.getWriter()) {
            String eventType = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try {
                    event = JSON.parseObject(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.getString("type");
                if (blank(type)) type = eventType;
                if ("response.output_text.delta".equals(type)) {
                    String delta = event.getString("delta");
                    if (!blank(delta)) writeChatDelta(writer, delta);
                }
            }
            writer.write("data: [DONE]\n\n");
            writer.flush();
        } finally {
            connection.disconnect();
        }
    }

    private void writeChatDelta(PrintWriter writer, String delta) {
        JSONObject deltaObject = new JSONObject(true);
        deltaObject.put("content", delta);
        JSONObject choice = new JSONObject(true);
        choice.put("index", 0);
        choice.put("delta", deltaObject);
        JSONArray choices = new JSONArray();
        choices.add(choice);
        JSONObject chunk = new JSONObject(true);
        chunk.put("choices", choices);
        writer.write("data: ");
        writer.write(chunk.toJSONString());
        writer.write("\n\n");
        writer.flush();
    }

    private void forwardResponsesJson(HttpURLConnection connection, InputStream input,
                                      HttpServletResponse response) throws IOException {
        String upstream = readAll(input);
        connection.disconnect();
        String content = extractResponseText(upstream);
        JSONObject message = new JSONObject(true);
        message.put("role", "assistant");
        message.put("content", content);
        JSONObject choice = new JSONObject(true);
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        JSONArray choices = new JSONArray();
        choices.add(choice);
        JSONObject result = new JSONObject(true);
        result.put("choices", choices);
        writeJson(response, 200, result);
    }

    private String extractResponseText(String body) {
        try {
            JSONObject root = JSON.parseObject(body);
            JSONArray output = root.getJSONArray("output");
            if (output == null) return "";
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < output.size(); i++) {
                JSONObject item = output.getJSONObject(i);
                if (item == null || !"message".equals(item.getString("type"))) continue;
                JSONArray content = item.getJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.size(); j++) {
                    JSONObject part = content.getJSONObject(j);
                    if (part != null && "output_text".equals(part.getString("type")))
                        text.append(part.getString("text"));
                }
            }
            return text.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void prepareSse(HttpServletResponse response) {
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
    }

    private void forwardChatStream(HttpURLConnection connection, InputStream input, HttpServletResponse response) throws IOException {
        prepareSse(response);
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

    private void forwardChatJson(HttpURLConnection connection, InputStream input, HttpServletResponse response) throws IOException {
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
