package com.bootdo.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.config.AiProperties;
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
public class GlmChatService {

    private static final Logger log = LoggerFactory.getLogger(GlmChatService.class);

    private final AiProperties aiProperties;
    private final GlmApiKeyProvider apiKeyProvider;

    public GlmChatService(AiProperties aiProperties, GlmApiKeyProvider apiKeyProvider) {
        this.aiProperties = aiProperties;
        this.apiKeyProvider = apiKeyProvider;
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

    public JSONObject buildTokenUsage(ChatCompletionRequest request) throws IOException {
        HttpURLConnection conn = openConnection(buildUpstreamPayload(request, false), false);
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);
        conn.disconnect();

        if (code >= 400) {
            throw new IOException("GLM upstream error: HTTP " + code + ", body=" + body);
        }

        JSONObject root = JSON.parseObject(body);
        JSONObject usage = extractUsage(root);
        if (usage == null) {
            usage = estimateUsage(request, root);
        }
        return usage;
    }

    private HttpURLConnection openConnection(String payload, boolean stream) throws IOException {
        String apiKey = apiKeyProvider.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("GLM apiKey is empty, please set ai.glm.apiKey or GLM_API_KEY");
        }

        // 按你的要求：每次请求都打印当前实际使用的 apiKey（仅最后 6 位，避免泄露）
        if (log.isInfoEnabled()) {
            String tail6 = lastN(apiKey.trim(), 6);
            log.info("GLM upstream request using apiKey tail6={}", tail6);
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

    private String lastN(String v, int n) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (n <= 0) {
            return "";
        }
        return s.length() <= n ? s : s.substring(s.length() - n);
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
                if (msg.getName() != null && !msg.getName().trim().isEmpty()) {
                    m.put("name", msg.getName().trim());
                }
                if (msg.getToolCallId() != null && !msg.getToolCallId().trim().isEmpty()) {
                    m.put("tool_call_id", msg.getToolCallId().trim());
                }
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    m.put("tool_calls", msg.getToolCalls());
                }
                messages.add(m);
            }
        }
        root.put("messages", messages);
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            root.put("tools", request.getTools());
        }
        if (request.getToolChoice() != null) {
            root.put("tool_choice", request.getToolChoice());
        }
        return root.toJSONString();
    }

    private String convertToOpenAiDelta(String data) {
        try {
            JSONObject obj = JSON.parseObject(data);
            JSONArray choices = obj.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONArray compatChoices = new JSONArray();
                for (int i = 0; i < choices.size(); i++) {
                    JSONObject upstreamChoice = choices.getJSONObject(i);
                    if (upstreamChoice == null) {
                        continue;
                    }

                    JSONObject compatChoice = new JSONObject();
                    if (upstreamChoice.containsKey("index")) {
                        compatChoice.put("index", upstreamChoice.get("index"));
                    }

                    JSONObject compatDelta = sanitizeDelta(upstreamChoice.getJSONObject("delta"));
                    if (!compatDelta.isEmpty()) {
                        compatChoice.put("delta", compatDelta);
                    }

                    if (upstreamChoice.containsKey("finish_reason")) {
                        compatChoice.put("finish_reason", upstreamChoice.get("finish_reason"));
                    }

                    if (!compatChoice.isEmpty()) {
                        compatChoices.add(compatChoice);
                        continue;
                    }

                    JSONObject compatMessage = sanitizeMessage(upstreamChoice.getJSONObject("message"));
                    if (!compatMessage.isEmpty()) {
                        JSONObject fallbackChoice = new JSONObject();
                        fallbackChoice.put("delta", compatMessage);
                        if (upstreamChoice.containsKey("finish_reason")) {
                            fallbackChoice.put("finish_reason", upstreamChoice.get("finish_reason"));
                        }
                        compatChoices.add(fallbackChoice);
                    }
                }

                if (!compatChoices.isEmpty()) {
                    JSONObject compatRoot = new JSONObject();
                    compatRoot.put("choices", compatChoices);
                    return compatRoot.toJSONString();
                }
            }
        } catch (Exception ignore) {
            // skip invalid upstream chunk
        }
        return null;
    }

    private String resolveModelForUpstream(String requestModel) {
        String defaultModel = aiProperties.getGlm().getModel();
        String model = requestModel == null ? "" : requestModel.trim();
        if (model.isEmpty()) {
            return defaultModel;
        }
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

    private JSONObject sanitizeDelta(JSONObject delta) {
        JSONObject compatDelta = new JSONObject();
        if (delta == null) {
            return compatDelta;
        }
        if (delta.containsKey("role")) {
            compatDelta.put("role", delta.get("role"));
        }
        if (delta.containsKey("content")) {
            Object content = delta.get("content");
            if (content != null) {
                compatDelta.put("content", content);
            }
        }
        if (delta.containsKey("tool_calls")) {
            Object toolCalls = delta.get("tool_calls");
            if (toolCalls != null) {
                compatDelta.put("tool_calls", toolCalls);
            }
        }
        return compatDelta;
    }

    private JSONObject sanitizeMessage(JSONObject message) {
        JSONObject compatMessage = new JSONObject();
        if (message == null) {
            return compatMessage;
        }
        if (message.containsKey("role")) {
            compatMessage.put("role", message.get("role"));
        }
        if (message.containsKey("content")) {
            Object content = message.get("content");
            if (content != null) {
                compatMessage.put("content", content);
            }
        }
        if (message.containsKey("tool_calls")) {
            Object toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                compatMessage.put("tool_calls", toolCalls);
            }
        }
        return compatMessage;
    }

    private JSONObject extractUsage(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONObject usage = root.getJSONObject("usage");
        if (usage != null) {
            Long prompt = firstLong(usage, "prompt_tokens", "input_tokens", "promptTokens", "inputTokens");
            Long completion = firstLong(usage, "completion_tokens", "output_tokens", "completionTokens", "outputTokens");
            Long total = firstLong(usage, "total_tokens", "totalTokens");
            if (prompt != null || completion != null || total != null) {
                return normalizeUsage(prompt, completion, total);
            }
        }
        return null;
    }

    private JSONObject estimateUsage(ChatCompletionRequest request, JSONObject root) {
        StringBuilder promptText = new StringBuilder();
        if (request != null && request.getMessages() != null) {
            for (ChatCompletionRequest.Message message : request.getMessages()) {
                if (message == null) {
                    continue;
                }
                if (message.getRole() != null) {
                    promptText.append(message.getRole()).append(':');
                }
                if (message.getContent() != null) {
                    promptText.append(message.getContent());
                }
                promptText.append('\n');
            }
        }

        String completionText = "";
        if (root != null) {
            JSONArray choices = root.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject first = choices.getJSONObject(0);
                if (first != null) {
                    JSONObject msg = first.getJSONObject("message");
                    if (msg != null) {
                        completionText = safe(msg.getString("content"));
                    }
                }
            }
        }

        long prompt = roughTokenCount(promptText.toString());
        long completion = roughTokenCount(completionText);
        return normalizeUsage(prompt, completion, prompt + completion);
    }

    private long roughTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        int length = text.trim().length();
        return Math.max(1, (long) Math.ceil(length / 4.0d));
    }

    private JSONObject normalizeUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
        long prompt = promptTokens == null ? 0L : Math.max(promptTokens, 0L);
        long completion = completionTokens == null ? 0L : Math.max(completionTokens, 0L);
        long total;
        if (totalTokens == null || totalTokens < 0) {
            total = prompt + completion;
        } else {
            total = totalTokens;
        }

        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        usage.put("total_tokens", total);
        return usage;
    }

    private Long firstLong(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = obj.get(key);
            Long converted = asLong(value);
            if (converted != null) {
                return converted;
            }
        }
        return null;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
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
        return sb.toString().trim();
    }

    private String errorJson(String code, String message, String upstream) {
        JSONObject err = new JSONObject();
        err.put("code", code);
        err.put("message", message);
        if (upstream != null && !upstream.trim().isEmpty()) {
            err.put("upstream", upstream);
        }
        JSONObject root = new JSONObject();
        root.put("error", err);
        return root.toJSONString();
    }
}
