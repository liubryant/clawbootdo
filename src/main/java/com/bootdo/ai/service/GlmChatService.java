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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GlmChatService {

    private static final Logger log = LoggerFactory.getLogger(GlmChatService.class);

    private static final String OBJECT_CHAT_COMPLETION = "chat.completion";
    private static final String OBJECT_CHAT_COMPLETION_CHUNK = "chat.completion.chunk";
    private static final String RESPONSE_MODE_IMAGE_ONLY = "image_only";
    private static final String RESPONSE_MODE_VIDEO_ONLY = "video_only";
    private static final String RESPONSE_MODE_DEFAULT = "default";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final AiProperties aiProperties;
    private final GlmApiKeyProvider apiKeyProvider;

    public GlmChatService(AiProperties aiProperties, GlmApiKeyProvider apiKeyProvider) {
        this.aiProperties = aiProperties;
        this.apiKeyProvider = apiKeyProvider;
    }

    public void streamCompletion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        if (shouldUseVideoGenerationRoute(request)) {
            streamVideoCompletion(request, response);
            return;
        }
        if (shouldUseImageGenerationRoute(request)) {
            streamImageCompletion(request, response);
            return;
        }

        HttpURLConnection conn = openChatConnection(buildUpstreamPayload(request, true), true);
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();

        if (code >= 400) {
            String err = readAll(stream);
            response.setStatus(502);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(errorJson("upstream_error", "chat upstream error: HTTP " + code, err));
            conn.disconnect();
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
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }

                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    writer.write("data: [DONE]\n\n");
                    writer.flush();
                    break;
                }

                String compat = convertToOpenAiDelta(data, request);
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
        if (shouldUseVideoGenerationRoute(request)) {
            nonStreamVideoCompletion(request, response);
            return;
        }
        if (shouldUseImageGenerationRoute(request)) {
            nonStreamImageCompletion(request, response);
            return;
        }

        HttpURLConnection conn = openChatConnection(buildUpstreamPayload(request, false), false);
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);

        response.setStatus(code >= 400 ? 502 : 200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(code >= 400 ? errorJson("upstream_error", "chat upstream error: HTTP " + code, body) : convertToOpenAiResponse(body, request));
        conn.disconnect();
    }

    public JSONObject buildTokenUsage(ChatCompletionRequest request) throws IOException {
        if (isImageOnlyResponse(request)) {
            JSONObject usage = new JSONObject();
            long promptTokens = roughTokenCount(buildImagePrompt(request));
            usage.put("prompt_tokens", promptTokens);
            usage.put("completion_tokens", 1L);
            usage.put("total_tokens", promptTokens + 1L);
            return usage;
        }
        if (isVideoOnlyResponse(request)) {
            JSONObject usage = new JSONObject();
            long promptTokens = roughTokenCount(buildVideoPrompt(request));
            usage.put("prompt_tokens", promptTokens);
            usage.put("completion_tokens", 1L);
            usage.put("total_tokens", promptTokens + 1L);
            return usage;
        }
        if (isImageGenerationRequest(request)) {
            JSONObject usage = new JSONObject();
            long promptTokens = roughTokenCount(buildImagePrompt(request));
            usage.put("prompt_tokens", promptTokens);
            usage.put("completion_tokens", 1L);
            usage.put("total_tokens", promptTokens + 1L);
            return usage;
        }

        HttpURLConnection conn = openChatConnection(buildUpstreamPayload(request, false), false);
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);
        conn.disconnect();

        if (code >= 400) {
            throw new IOException("chat upstream error: HTTP " + code + ", body=" + body);
        }

        JSONObject root = JSON.parseObject(body);
        JSONObject usage = extractUsage(root);
        return usage == null ? estimateUsage(request, root) : usage;
    }

    private void streamImageCompletion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        HttpURLConnection conn = openImageConnection(buildImagePayload(request));
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);

        if (code >= 400) {
            response.setStatus(502);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(errorJson("upstream_error", "GLM image upstream error: HTTP " + code, body));
            conn.disconnect();
            return;
        }

        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        JSONObject compat = convertImageResponseToOpenAiJson(body, request);
        JSONObject message = firstChoiceMessage(compat);
        String content = message == null ? "" : safe(message.getString("content"));
        JSONArray images = message == null ? null : message.getJSONArray("images");
        String id = safeTrim(compat.getString("id"), "chatcmpl-image-" + System.currentTimeMillis());
        long created = compat.getLongValue("created") > 0 ? compat.getLongValue("created") : System.currentTimeMillis() / 1000L;
        String model = safeTrim(compat.getString("model"), resolveImageModel(request == null ? null : request.getModel()));

        try (PrintWriter writer = response.getWriter()) {
            writer.write("data: " + buildChunk(id, created, model, roleDelta("assistant"), null) + "\n\n");
            writer.flush();

            if (!content.isEmpty()) {
                writer.write("data: " + buildChunk(id, created, model, contentDelta(content, images, null), null) + "\n\n");
                writer.flush();
            }

            writer.write("data: " + buildChunk(id, created, model, new JSONObject(), "stop") + "\n\n");
            writer.write("data: [DONE]\n\n");
            writer.flush();
        } finally {
            conn.disconnect();
        }
    }

    private void nonStreamImageCompletion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        HttpURLConnection conn = openImageConnection(buildImagePayload(request));
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);

        response.setStatus(code >= 400 ? 502 : 200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(code >= 400 ? errorJson("upstream_error", "GLM image upstream error: HTTP " + code, body) : convertImageResponseToOpenAiJson(body, request).toJSONString());
        conn.disconnect();
    }

    public void videoGenerations(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        JSONObject submitted = submitVideoGeneration(request);
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(submitted.toJSONString());
    }

    public void videoGenerationResult(String taskId, HttpServletResponse response) throws IOException {
        JSONObject result = fetchVideoGenerationResult(taskId);
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(result.toJSONString());
    }

    private void streamVideoCompletion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        JSONObject upstream = awaitVideoGenerationResult(request);

        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        JSONObject compat = convertVideoResponseToOpenAiJson(upstream == null ? "{}" : upstream.toJSONString(), request);
        JSONObject message = firstChoiceMessage(compat);
        String content = message == null ? "" : safe(message.getString("content"));
        JSONArray videos = message == null ? null : message.getJSONArray("videos");
        String id = safeTrim(compat.getString("id"), "chatcmpl-video-" + System.currentTimeMillis());
        long created = compat.getLongValue("created") > 0 ? compat.getLongValue("created") : System.currentTimeMillis() / 1000L;
        String model = safeTrim(compat.getString("model"), resolveVideoModel(request == null ? null : request.getModel()));

        try (PrintWriter writer = response.getWriter()) {
            writer.write("data: " + buildChunk(id, created, model, roleDelta("assistant"), null) + "\n\n");
            writer.flush();

            if (!content.isEmpty()) {
                writer.write("data: " + buildChunk(id, created, model, contentDelta(content, null, videos), null) + "\n\n");
                writer.flush();
            }

            writer.write("data: " + buildChunk(id, created, model, new JSONObject(), "stop") + "\n\n");
            writer.write("data: [DONE]\n\n");
            writer.flush();
        }
    }

    private void nonStreamVideoCompletion(ChatCompletionRequest request, HttpServletResponse response) throws IOException {
        JSONObject upstream = awaitVideoGenerationResult(request);
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(convertVideoResponseToOpenAiJson(upstream == null ? "{}" : upstream.toJSONString(), request).toJSONString());
    }

    private HttpURLConnection openChatConnection(String payload, boolean stream) throws IOException {
        return openDynamicChatJsonConnection("/chat/completions", payload, stream ? "text/event-stream" : "application/json");
    }

    private HttpURLConnection openImageConnection(String payload) throws IOException {
        return openGlmJsonConnection("/images/generations", payload, "application/json");
    }

    private HttpURLConnection openVideoConnection(String payload) throws IOException {
        return openGlmJsonConnection("/videos/generations", payload, "application/json");
    }

    private HttpURLConnection openAsyncResultConnection(String taskId) throws IOException {
        String apiKey = apiKeyProvider.getGlmRouteApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("GLM apiKey is empty, please set ai.glm.apiKey or GLM_API_KEY");
        }

        String base = safeTrim(apiKeyProvider.getGlmRouteBaseUrl(), "https://open.bigmodel.cn/api/paas/v4");
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String url = normalizedBase + "/async-result/" + safe(taskId).trim();

        if (!url.toLowerCase().startsWith("https://")) {
            throw new IOException("GLM async-result baseUrl must use HTTPS, got: " + url);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setDoInput(true);
        conn.setConnectTimeout(aiProperties.getConnectTimeoutMs());
        conn.setReadTimeout(aiProperties.getReadTimeoutMs());
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        return conn;
    }

    private HttpURLConnection openDynamicChatJsonConnection(String path, String payload, String accept) throws IOException {
        DynamicChatUpstreamConfig chatConfig = apiKeyProvider.getChatConfig();
        String apiKey = safe(chatConfig.getApiKey()).trim();
        if (apiKey.isEmpty()) {
            throw new IOException("chat upstream apiKey is empty, please set ai.glm.apiKey or api/info data.aiApiKey");
        }

        String provider = safeTrim(chatConfig.getProvider(), "glm");
        String base = safeTrim(chatConfig.getBaseUrl(), safeTrim(aiProperties.getGlm().getBaseUrl(), "https://open.bigmodel.cn/api/paas/v4"));

        if (log.isInfoEnabled()) {
            log.info("Chat upstream request provider={}, path={}, model={}, apiKey tail6={}",
                    provider, path, safeTrim(chatConfig.getModel()), lastN(apiKey, 6));
        }

        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;

        if (!url.toLowerCase().startsWith("https://")) {
            throw new IOException("chat upstream baseUrl must use HTTPS, got: " + url);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(aiProperties.getConnectTimeoutMs());
        conn.setReadTimeout(aiProperties.getReadTimeoutMs());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return conn;
    }

    private HttpURLConnection openGlmJsonConnection(String path, String payload, String accept) throws IOException {
        String apiKey = apiKeyProvider.getGlmRouteApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("GLM apiKey is empty, please set ai.glm.apiKey or GLM_API_KEY");
        }

        if (log.isInfoEnabled()) {
            log.info("GLM upstream request path={}, apiKey tail6={}", path, lastN(apiKey.trim(), 6));
        }

        String base = safeTrim(apiKeyProvider.getGlmRouteBaseUrl(), "https://open.bigmodel.cn/api/paas/v4");
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;

        if (!url.toLowerCase().startsWith("https://")) {
            throw new IOException("GLM upstream baseUrl must use HTTPS, got: " + url);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(aiProperties.getConnectTimeoutMs());
        conn.setReadTimeout(aiProperties.getReadTimeoutMs());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return conn;
    }

    private String buildUpstreamPayload(ChatCompletionRequest request, boolean stream) {
        JSONObject root = new JSONObject();
        root.put("model", resolveModelForUpstream(request == null ? null : request.getModel()));
        root.put("stream", stream);
        if (request != null && request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }

        JSONArray messages = new JSONArray();
        if (request != null && request.getMessages() != null) {
            for (ChatCompletionRequest.Message msg : request.getMessages()) {
                if (msg == null) {
                    continue;
                }
                JSONObject m = new JSONObject();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                if (!safe(msg.getName()).trim().isEmpty()) {
                    m.put("name", msg.getName().trim());
                }
                if (!safe(msg.getToolCallId()).trim().isEmpty()) {
                    m.put("tool_call_id", msg.getToolCallId().trim());
                }
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    m.put("tool_calls", msg.getToolCalls());
                }
                messages.add(m);
            }
        }
        root.put("messages", messages);

        if (request != null && request.getTools() != null && !request.getTools().isEmpty()) {
            root.put("tools", request.getTools());
        }
        if (request != null && request.getToolChoice() != null) {
            root.put("tool_choice", request.getToolChoice());
        }
        return root.toJSONString();
    }

    private String buildImagePayload(ChatCompletionRequest request) {
        JSONObject root = new JSONObject();
        root.put("model", resolveImageModel(request == null ? null : request.getModel()));
        root.put("prompt", buildImagePrompt(request));
        root.put("size", safeTrim(aiProperties.getGlm().getImageSize(), "1280x1280"));
        root.put("quality", safeTrim(aiProperties.getGlm().getImageQuality(), "hd"));
        Boolean watermarkEnabled = aiProperties.getGlm().getImageWatermarkEnabled();
        root.put("watermark_enabled", watermarkEnabled != null && watermarkEnabled);
        return root.toJSONString();
    }

    private String buildVideoPayload(ChatCompletionRequest request) {
        JSONObject root = new JSONObject();
        root.put("model", resolveVideoModel(request == null ? null : request.getModel()));
        root.put("prompt", buildVideoPrompt(request));
        root.put("quality", safeTrim(request == null ? null : request.getQuality(), safeTrim(aiProperties.getGlm().getVideoQuality(), "quality")));
        Boolean withAudio = request == null ? null : request.getWithAudio();
        if (withAudio == null) {
            withAudio = aiProperties.getGlm().getVideoWithAudio();
        }
        root.put("with_audio", withAudio == null || withAudio);
        root.put("size", safeTrim(request == null ? null : request.getSize(), safeTrim(aiProperties.getGlm().getVideoSize(), "1920x1080")));
        Integer fps = request == null ? null : request.getFps();
        if (fps == null || fps <= 0) {
            fps = aiProperties.getGlm().getVideoFps();
        }
        root.put("fps", fps == null || fps <= 0 ? 30 : fps);
        return root.toJSONString();
    }

    private JSONObject submitVideoGeneration(ChatCompletionRequest request) throws IOException {
        HttpURLConnection conn = openVideoConnection(buildVideoPayload(request));
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);
            if (code >= 400) {
                throw new IOException("GLM video upstream error: HTTP " + code + ", body=" + body);
            }
            return JSON.parseObject(body);
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject fetchVideoGenerationResult(String taskId) throws IOException {
        HttpURLConnection conn = openAsyncResultConnection(taskId);
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);
            if (code >= 400) {
                throw new IOException("GLM async-result upstream error: HTTP " + code + ", body=" + body);
            }
            return JSON.parseObject(body);
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject awaitVideoGenerationResult(ChatCompletionRequest request) throws IOException {
        JSONObject submitted = submitVideoGeneration(request);
        String taskId = safe(submitted.getString("id")).trim();
        if (taskId.isEmpty()) {
            return submitted;
        }

        long timeoutMs = Math.max(10000L, aiProperties.getGlm().getVideoPollTimeoutMs() == null ? 600000L : aiProperties.getGlm().getVideoPollTimeoutMs());
        long intervalMs = Math.max(1000L, aiProperties.getGlm().getVideoPollIntervalMs() == null ? 5000L : aiProperties.getGlm().getVideoPollIntervalMs());
        long deadline = System.currentTimeMillis() + timeoutMs;
        JSONObject latest = submitted;

        while (System.currentTimeMillis() <= deadline) {
            latest = fetchVideoGenerationResult(taskId);
            if (hasCompletedVideoResult(latest)) {
                return latest;
            }
            if (isFailedVideoTask(latest)) {
                return latest;
            }
            sleepQuietly(intervalMs);
        }
        latest.put("task_status", safeTrim(latest.getString("task_status"), "TIMEOUT"));
        return latest;
    }

    private String convertToOpenAiDelta(String data, ChatCompletionRequest request) {
        try {
            JSONObject obj = JSON.parseObject(data);
            JSONArray choices = obj.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }

            JSONArray compatChoices = new JSONArray();
            for (int i = 0; i < choices.size(); i++) {
                JSONObject upstreamChoice = choices.getJSONObject(i);
                if (upstreamChoice == null) {
                    continue;
                }

                JSONObject compatChoice = new JSONObject();
                compatChoice.put("index", upstreamChoice.containsKey("index") ? upstreamChoice.get("index") : i);

                JSONObject compatDelta = sanitizeDelta(upstreamChoice.getJSONObject("delta"), request);
                if (compatDelta.isEmpty()) {
                    compatDelta = sanitizeMessage(upstreamChoice.getJSONObject("message"), request);
                }
                if (!compatDelta.isEmpty()) {
                    compatChoice.put("delta", compatDelta);
                }

                if (upstreamChoice.containsKey("finish_reason")) {
                    compatChoice.put("finish_reason", upstreamChoice.get("finish_reason"));
                }
                if (compatChoice.containsKey("delta") || compatChoice.containsKey("finish_reason")) {
                    compatChoices.add(compatChoice);
                }
            }

            if (compatChoices.isEmpty()) {
                return null;
            }
            JSONObject compatRoot = new JSONObject();
            compatRoot.put("id", obj.getString("id"));
            compatRoot.put("object", OBJECT_CHAT_COMPLETION_CHUNK);
            compatRoot.put("created", obj.getLongValue("created"));
            compatRoot.put("model", obj.getString("model"));
            compatRoot.put("choices", compatChoices);
            return compatRoot.toJSONString();
        } catch (Exception ignore) {
            return null;
        }
    }

    private String convertToOpenAiResponse(String body, ChatCompletionRequest request) {
        if (body == null || body.trim().isEmpty()) {
            return "{}";
        }
        try {
            JSONObject root = JSON.parseObject(body);
            JSONArray choices = root.getJSONArray("choices");
            if (choices != null) {
                for (int i = 0; i < choices.size(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    JSONObject message = choice == null ? null : choice.getJSONObject("message");
                    if (message != null) {
                        message.remove("reasoning_content");
                        JSONObject sanitized = sanitizeMessage(message, request);
                        choice.put("message", sanitized);
                    }
                }
            }
            return root.toJSONString();
        } catch (Exception ignore) {
            return body;
        }
    }

    private JSONObject convertImageResponseToOpenAiJson(String body, ChatCompletionRequest request) {
        JSONObject root = new JSONObject();
        try {
            JSONObject upstream = JSON.parseObject(body);
            JSONArray images = extractImages(upstream);
            String prompt = buildImagePrompt(request);
            String content = buildImageMarkdownContent(prompt, images);

            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", content);
            if (!images.isEmpty()) {
                message.put("images", images);
            }

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");

            JSONArray choices = new JSONArray();
            choices.add(choice);

            long created = upstream.getLongValue("created") > 0 ? upstream.getLongValue("created") : System.currentTimeMillis() / 1000L;
            root.put("id", safeTrim(upstream.getString("id"), "chatcmpl-image-" + System.currentTimeMillis()));
            root.put("object", OBJECT_CHAT_COMPLETION);
            root.put("created", created);
            root.put("model", resolveImageModel(request == null ? null : request.getModel()));
            root.put("choices", choices);
            root.put("request_id", upstream.getString("request_id"));
            root.put("usage", imageUsage(prompt, images.size()));
            root.put("upstream", upstream);
            return root;
        } catch (Exception ex) {
            log.warn("convert image response to openai response failed", ex);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", body == null ? "" : body);
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");
            JSONArray choices = new JSONArray();
            choices.add(choice);
            root.put("id", "chatcmpl-image-" + System.currentTimeMillis());
            root.put("object", OBJECT_CHAT_COMPLETION);
            root.put("created", System.currentTimeMillis() / 1000L);
            root.put("model", resolveImageModel(request == null ? null : request.getModel()));
            root.put("choices", choices);
            root.put("usage", imageUsage(buildImagePrompt(request), 0));
            return root;
        }
    }

    private JSONObject convertVideoResponseToOpenAiJson(String body, ChatCompletionRequest request) {
        JSONObject root = new JSONObject();
        try {
            JSONObject upstream = JSON.parseObject(body);
            String prompt = buildVideoPrompt(request);
            JSONArray videos = extractVideos(upstream);
            String content = buildVideoMarkdownContent(prompt, upstream, videos);

            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", content);
            if (!videos.isEmpty()) {
                message.put("videos", videos);
            }
            if (!safe(upstream.getString("task_status")).trim().isEmpty()) {
                message.put("task_status", upstream.getString("task_status"));
            }

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");

            JSONArray choices = new JSONArray();
            choices.add(choice);

            long created = upstream.getLongValue("created") > 0 ? upstream.getLongValue("created") : System.currentTimeMillis() / 1000L;
            root.put("id", safeTrim(upstream.getString("id"), "chatcmpl-video-" + System.currentTimeMillis()));
            root.put("object", OBJECT_CHAT_COMPLETION);
            root.put("created", created);
            root.put("model", resolveVideoModel(request == null ? null : request.getModel()));
            root.put("choices", choices);
            root.put("request_id", upstream.getString("request_id"));
            root.put("task_status", upstream.getString("task_status"));
            root.put("usage", mediaUsage(prompt, videos.size()));
            root.put("upstream", upstream);
            return root;
        } catch (Exception ex) {
            log.warn("convert video response to openai response failed", ex);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", body == null ? "" : body);
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");
            JSONArray choices = new JSONArray();
            choices.add(choice);
            root.put("id", "chatcmpl-video-" + System.currentTimeMillis());
            root.put("object", OBJECT_CHAT_COMPLETION);
            root.put("created", System.currentTimeMillis() / 1000L);
            root.put("model", resolveVideoModel(request == null ? null : request.getModel()));
            root.put("choices", choices);
            root.put("usage", mediaUsage(buildVideoPrompt(request), 0));
            return root;
        }
    }

    private JSONArray extractImages(JSONObject upstream) {
        JSONArray images = new JSONArray();
        JSONArray upstreamData = upstream == null ? null : upstream.getJSONArray("data");
        if (upstreamData == null) {
            return images;
        }
        for (int i = 0; i < upstreamData.size(); i++) {
            JSONObject item = upstreamData.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String url = safe(item.getString("url")).trim();
            if (url.isEmpty()) {
                continue;
            }
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", url);
            JSONObject image = new JSONObject();
            image.put("type", "image_url");
            image.put("image_url", imageUrl);
            images.add(image);
        }
        return images;
    }

    private JSONArray extractVideos(JSONObject upstream) {
        JSONArray videos = new JSONArray();
        JSONArray upstreamData = upstream == null ? null : upstream.getJSONArray("data");
        if (upstreamData != null) {
            for (int i = 0; i < upstreamData.size(); i++) {
                JSONObject item = upstreamData.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String url = safe(item.getString("url")).trim();
                if (url.isEmpty()) {
                    url = safe(item.getString("video_url")).trim();
                }
                if (url.isEmpty()) {
                    continue;
                }
                JSONObject videoUrl = new JSONObject();
                videoUrl.put("url", url);
                JSONObject video = new JSONObject();
                video.put("type", "video_url");
                video.put("video_url", videoUrl);
                videos.add(video);
            }
        }
        JSONArray videoResult = upstream == null ? null : upstream.getJSONArray("video_result");
        if (videoResult != null) {
            for (int i = 0; i < videoResult.size(); i++) {
                JSONObject item = videoResult.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String url = safe(item.getString("url")).trim();
                if (url.isEmpty()) {
                    continue;
                }
                JSONObject videoUrl = new JSONObject();
                videoUrl.put("url", url);
                JSONObject video = new JSONObject();
                video.put("type", "video_url");
                video.put("video_url", videoUrl);
                videos.add(video);
            }
        }
        return videos;
    }

    private JSONObject firstChoiceMessage(JSONObject root) {
        JSONArray choices = root == null ? null : root.getJSONArray("choices");
        JSONObject firstChoice = choices == null || choices.isEmpty() ? null : choices.getJSONObject(0);
        return firstChoice == null ? null : firstChoice.getJSONObject("message");
    }

    private JSONObject buildChunk(String id, long created, String model, JSONObject delta, String finishReason) {
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta == null ? new JSONObject() : delta);
        choice.put("finish_reason", finishReason);
        JSONArray choices = new JSONArray();
        choices.add(choice);

        JSONObject chunk = new JSONObject();
        chunk.put("id", id);
        chunk.put("object", OBJECT_CHAT_COMPLETION_CHUNK);
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", choices);
        return chunk;
    }

    private JSONObject roleDelta(String role) {
        JSONObject delta = new JSONObject();
        delta.put("role", role);
        return delta;
    }

    private JSONObject contentDelta(String content, JSONArray images, JSONArray videos) {
        JSONObject delta = new JSONObject();
        delta.put("content", content);
        if (images != null && !images.isEmpty()) {
            delta.put("images", images);
        }
        if (videos != null && !videos.isEmpty()) {
            delta.put("videos", videos);
        }
        return delta;
    }

    private JSONObject sanitizeDelta(JSONObject delta, ChatCompletionRequest request) {
        JSONObject compatDelta = new JSONObject();
        if (delta == null) {
            return compatDelta;
        }
        copyIfPresent(delta, compatDelta, "role");
        return applyResponseMode(compatDelta, delta, request, true);
    }

    private JSONObject sanitizeMessage(JSONObject message, ChatCompletionRequest request) {
        JSONObject compatMessage = new JSONObject();
        if (message == null) {
            return compatMessage;
        }
        copyIfPresent(message, compatMessage, "role");
        return applyResponseMode(compatMessage, message, request, false);
    }

    private void copyIfPresent(JSONObject from, JSONObject to, String key) {
        if (from.containsKey(key) && from.get(key) != null) {
            to.put(key, from.get(key));
        }
    }

    private boolean shouldUseImageGenerationRoute(ChatCompletionRequest request) {
        if (isImageOnlyResponse(request)) {
            return true;
        }
        return isImageGenerationRequest(request);
    }

    private boolean shouldUseVideoGenerationRoute(ChatCompletionRequest request) {
        return isVideoOnlyResponse(request);
    }

    private boolean isImageGenerationRequest(ChatCompletionRequest request) {
        if (request == null) {
            return false;
        }
        String model = safe(request.getModel()).trim();
        return "glm-image".equalsIgnoreCase(model);
    }

    private JSONObject applyResponseMode(JSONObject target, JSONObject source, ChatCompletionRequest request, boolean delta) {
        String mode = resolveResponseMode(request);
        if (RESPONSE_MODE_IMAGE_ONLY.equals(mode)) {
            String imageContent = extractImageOnlyContent(source);
            if (!imageContent.isEmpty()) {
                target.put("content", imageContent);
            }
            JSONArray images = extractImagesArray(source);
            if (images != null && !images.isEmpty()) {
                target.put("images", images);
            }
            if (!delta && !target.containsKey("content") && (images == null || images.isEmpty())) {
                target.put("content", "");
            }
            return target;
        }
        if (RESPONSE_MODE_VIDEO_ONLY.equals(mode)) {
            String videoContent = extractVideoOnlyContent(source);
            if (!videoContent.isEmpty()) {
                target.put("content", videoContent);
            }
            JSONArray videos = extractVideosArray(source);
            if (videos != null && !videos.isEmpty()) {
                target.put("videos", videos);
            }
            if (!delta && !target.containsKey("content") && (videos == null || videos.isEmpty())) {
                target.put("content", "");
            }
            return target;
        }

        String textOnlyContent = stripMediaFromContent(source.get("content"));
        if (!textOnlyContent.isEmpty()) {
            target.put("content", textOnlyContent);
        } else if (!delta && source.containsKey("content")) {
            target.put("content", "");
        }
        copyIfPresent(source, target, "tool_calls");
        return target;
    }

    private String resolveResponseMode(ChatCompletionRequest request) {
        String mode = safe(request == null ? null : request.getResponseMode()).trim().toLowerCase();
        if (RESPONSE_MODE_IMAGE_ONLY.equals(mode) || RESPONSE_MODE_VIDEO_ONLY.equals(mode)) {
            return mode;
        }
        return RESPONSE_MODE_DEFAULT;
    }

    private boolean isImageOnlyResponse(ChatCompletionRequest request) {
        return RESPONSE_MODE_IMAGE_ONLY.equals(resolveResponseMode(request));
    }

    private boolean isVideoOnlyResponse(ChatCompletionRequest request) {
        return RESPONSE_MODE_VIDEO_ONLY.equals(resolveResponseMode(request));
    }

    private String extractImageOnlyContent(JSONObject source) {
        Set<String> urls = new LinkedHashSet<String>();
        collectImageUrls(source == null ? null : source.get("content"), urls);
        JSONArray images = extractImagesArray(source);
        if (images != null) {
            for (int i = 0; i < images.size(); i++) {
                Object item = images.get(i);
                if (item instanceof JSONObject) {
                    String url = extractImageUrlFromBlock((JSONObject) item);
                    if (!url.isEmpty()) {
                        urls.add(url);
                    }
                }
            }
        }
        return joinUrls(urls);
    }

    private String extractVideoOnlyContent(JSONObject source) {
        Set<String> urls = new LinkedHashSet<String>();
        collectVideoUrls(source == null ? null : source.get("content"), urls);
        Object videos = source == null ? null : source.get("videos");
        if (videos instanceof JSONArray) {
            JSONArray array = (JSONArray) videos;
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                if (item instanceof JSONObject) {
                    String url = extractVideoUrlFromBlock((JSONObject) item);
                    if (!url.isEmpty()) {
                        urls.add(url);
                    }
                }
            }
        }
        return joinUrls(urls);
    }

    private JSONArray extractImagesArray(JSONObject source) {
        if (source == null) {
            return null;
        }
        Object images = source.get("images");
        if (images instanceof JSONArray) {
            return (JSONArray) images;
        }
        return null;
    }

    private JSONArray extractVideosArray(JSONObject source) {
        if (source == null) {
            return null;
        }
        Object videos = source.get("videos");
        if (videos instanceof JSONArray) {
            return (JSONArray) videos;
        }
        return null;
    }

    private void collectImageUrls(Object content, Set<String> urls) {
        if (content == null || urls == null) {
            return;
        }
        if (content instanceof String) {
            urls.addAll(extractMatchingUrls((String) content, true));
            return;
        }
        if (content instanceof List) {
            for (Object item : (List<?>) content) {
                collectImageUrlFromPart(item, urls);
            }
            return;
        }
        if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.size(); i++) {
                collectImageUrlFromPart(array.get(i), urls);
            }
        }
    }

    private void collectVideoUrls(Object content, Set<String> urls) {
        if (content == null || urls == null) {
            return;
        }
        if (content instanceof String) {
            urls.addAll(extractMatchingUrls((String) content, false));
            return;
        }
        if (content instanceof List) {
            for (Object item : (List<?>) content) {
                collectVideoUrlFromPart(item, urls);
            }
            return;
        }
        if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.size(); i++) {
                collectVideoUrlFromPart(array.get(i), urls);
            }
        }
    }

    private void collectImageUrlFromPart(Object item, Set<String> urls) {
        JSONObject obj = toJsonObject(item);
        if (obj == null) {
            return;
        }
        String url = extractImageUrlFromBlock(obj);
        if (!url.isEmpty()) {
            urls.add(url);
        }
    }

    private void collectVideoUrlFromPart(Object item, Set<String> urls) {
        JSONObject obj = toJsonObject(item);
        if (obj == null) {
            return;
        }
        String url = extractVideoUrlFromBlock(obj);
        if (!url.isEmpty()) {
            urls.add(url);
        }
    }

    private JSONObject toJsonObject(Object item) {
        if (item instanceof JSONObject) {
            return (JSONObject) item;
        }
        if (item instanceof Map) {
            return new JSONObject((Map<String, Object>) item);
        }
        return null;
    }

    private String extractImageUrlFromBlock(JSONObject obj) {
        if (obj == null) {
            return "";
        }
        String type = safe(obj.getString("type")).trim().toLowerCase();
        if (!"image".equals(type) && !"image_url".equals(type)) {
            return "";
        }
        JSONObject nested = obj.getJSONObject("image_url");
        String direct = safe(obj.getString("url")).trim();
        String nestedUrl = nested == null ? "" : safe(nested.getString("url")).trim();
        return normalizeUrl(!nestedUrl.isEmpty() ? nestedUrl : direct);
    }

    private String extractVideoUrlFromBlock(JSONObject obj) {
        if (obj == null) {
            return "";
        }
        String type = safe(obj.getString("type")).trim().toLowerCase();
        if (!"video".equals(type) && !"video_url".equals(type)) {
            return "";
        }
        JSONObject nested = obj.getJSONObject("video_url");
        String direct = safe(obj.getString("url")).trim();
        String nestedUrl = nested == null ? "" : safe(nested.getString("url")).trim();
        return normalizeUrl(!nestedUrl.isEmpty() ? nestedUrl : direct);
    }

    private String stripMediaFromContent(Object content) {
        String text = extractTextContent(content);
        if (text.isEmpty()) {
            return "";
        }
        List<String> mediaUrls = new ArrayList<String>();
        mediaUrls.addAll(extractMatchingUrls(text, true));
        mediaUrls.addAll(extractMatchingUrls(text, false));
        String sanitized = text;
        for (String mediaUrl : mediaUrls) {
            if (!mediaUrl.isEmpty()) {
                sanitized = sanitized.replace(mediaUrl, "");
            }
        }
        sanitized = sanitized.replaceAll("!\\[[^\\]]*]\\([^\\)\\s]+\\)", "");
        sanitized = sanitized.replaceAll("(?m)^\\s*(图片链接|Image link|视频链接|Video link)\\s*[:：].*$", "");
        sanitized = sanitized.replaceAll("\\n{3,}", "\n\n").trim();
        return sanitized;
    }

    private List<String> extractMatchingUrls(String text, boolean image) {
        List<String> urls = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) {
            return urls;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = normalizeUrl(matcher.group());
            if (url.isEmpty()) {
                continue;
            }
            if (image ? isImageUrl(url) : isVideoUrl(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private boolean isImageUrl(String url) {
        String lower = safe(url).trim().toLowerCase();
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.contains("ufileos.com/");
    }

    private boolean isVideoUrl(String url) {
        String lower = safe(url).trim().toLowerCase();
        return lower.endsWith(".mp4")
                || lower.endsWith(".mov")
                || lower.endsWith(".m4v")
                || lower.endsWith(".webm")
                || lower.endsWith(".m3u8");
    }

    private String normalizeUrl(String url) {
        String trimmed = safe(url).trim();
        while (!trimmed.isEmpty()) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ')' || last == ']' || last == '>') {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            } else {
                break;
            }
        }
        return trimmed;
    }

    private String joinUrls(Set<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(url.trim());
        }
        return sb.toString();
    }

    private boolean looksLikeChineseDrawRequest(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        if (containsAny(lower,
                "\u4e0d\u8981\u753b",
                "\u522b\u753b",
                "\u4e0d\u7528\u753b",
                "\u4e0d\u9700\u8981\u753b",
                "\u4e0d\u8981\u751f\u6210\u56fe",
                "\u522b\u751f\u6210\u56fe")) {
            return false;
        }

        String compact = text.replaceAll("\\s+", "");
        return compact.matches(".*(\u8bf7|\u5e2e\u6211|\u7ed9\u6211|\u5e2e|\u60f3\u8981|\u6765|\u7528)?(\u753b|\u7ed8\u5236|\u753b\u51fa|\u751f\u6210|\u751f\u56fe|\u51fa\u56fe|\u505a\u56fe|\u4f5c\u56fe)[\u4e00-\u9fa5A-Za-z0-9_\\-]{1,80}.*")
                || compact.matches(".*[\u4e00-\u9fa5A-Za-z0-9_\\-]{1,40}(\u7684)?(\u56fe|\u56fe\u7247|\u56fe\u50cf|\u7167\u7247|\u6d77\u62a5|\u63d2\u753b|\u5934\u50cf).*");
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String buildImagePrompt(ChatCompletionRequest request) {
        return buildGenerationPrompt(request);
    }

    private String buildVideoPrompt(ChatCompletionRequest request) {
        return buildGenerationPrompt(request);
    }

    private String buildGenerationPrompt(ChatCompletionRequest request) {
        if (request == null) {
            return "";
        }
        String directPrompt = safe(request.getPrompt()).trim();
        if (!directPrompt.isEmpty()) {
            return directPrompt;
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }

        String lastUserText = "";
        for (ChatCompletionRequest.Message message : request.getMessages()) {
            if (message == null || !"user".equalsIgnoreCase(safe(message.getRole()).trim())) {
                continue;
            }
            String text = extractTextContent(message.getContent());
            if (!text.isEmpty()) {
                lastUserText = text;
            }
        }
        if (!lastUserText.isEmpty()) {
            return lastUserText;
        }

        StringBuilder prompt = new StringBuilder();
        for (ChatCompletionRequest.Message message : request.getMessages()) {
            if (message == null) {
                continue;
            }
            String text = extractTextContent(message.getContent());
            if (text.isEmpty()) {
                continue;
            }
            if (prompt.length() > 0) {
                prompt.append('\n');
            }
            prompt.append(text);
        }
        return prompt.toString().trim();
    }

    private String extractTextContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String) {
            return ((String) content).trim();
        }
        if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            List<?> list = (List<?>) content;
            for (Object item : list) {
                String part = extractContentPartText(item);
                if (part.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part);
            }
            return sb.toString().trim();
        }
        if (content instanceof Map) {
            return extractContentPartText(content);
        }
        return String.valueOf(content).trim();
    }

    private String extractContentPartText(Object item) {
        if (!(item instanceof Map)) {
            return item == null ? "" : String.valueOf(item).trim();
        }
        Map<?, ?> map = (Map<?, ?>) item;
        Object type = map.get("type");
        if (type != null && !"text".equalsIgnoreCase(String.valueOf(type))) {
            return "";
        }
        Object text = map.get("text");
        return text == null ? "" : String.valueOf(text).trim();
    }

    private String buildImageMarkdownContent(String prompt, JSONArray images) {
        if (images == null || images.isEmpty()) {
            return "\u56fe\u7247\u751f\u6210\u5b8c\u6210\uff0c\u4f46\u672a\u62ff\u5230\u53ef\u5c55\u793a\u7684\u56fe\u7247\u5730\u5740\u3002";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\u5df2\u4e3a\u4f60\u751f\u6210\u56fe\u7247\u3002");
        if (prompt != null && !prompt.trim().isEmpty()) {
            sb.append("\n\n").append("\u63d0\u793a\u8bcd\uff1a").append(prompt.trim());
        }
        sb.append("\n\n");
        for (int i = 0; i < images.size(); i++) {
            JSONObject image = images.getJSONObject(i);
            JSONObject imageUrl = image == null ? null : image.getJSONObject("image_url");
            String url = imageUrl == null ? "" : safe(imageUrl.getString("url")).trim();
            if (url.isEmpty()) {
                continue;
            }
            sb.append("![generated-image-").append(i + 1).append("](").append(url).append(")\n\n");
            sb.append("\u56fe\u7247\u94fe\u63a5").append(i + 1).append(": ").append(url).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String buildVideoMarkdownContent(String prompt, JSONObject upstream, JSONArray videos) {
        String taskStatus = upstream == null ? "" : safe(upstream.getString("task_status")).trim();
        String taskId = upstream == null ? "" : safe(upstream.getString("id")).trim();
        String requestId = upstream == null ? "" : safe(upstream.getString("request_id")).trim();
        if (videos == null || videos.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("\u89c6\u9891\u751f\u6210\u4efb\u52a1\u5df2\u63d0\u4ea4\u3002");
            if (!prompt.isEmpty()) {
                sb.append("\n\n").append("\u63d0\u793a\u8bcd\uff1a").append(prompt);
            }
            if (!taskStatus.isEmpty()) {
                sb.append("\n\n").append("task_status: ").append(taskStatus);
            }
            if (!taskId.isEmpty()) {
                sb.append("\n").append("id: ").append(taskId);
            }
            if (!requestId.isEmpty()) {
                sb.append("\n").append("request_id: ").append(requestId);
            }
            return sb.toString().trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\u5df2\u4e3a\u4f60\u751f\u6210\u89c6\u9891\u3002");
        if (prompt != null && !prompt.trim().isEmpty()) {
            sb.append("\n\n").append("\u63d0\u793a\u8bcd\uff1a").append(prompt.trim());
        }
        sb.append("\n\n");
        for (int i = 0; i < videos.size(); i++) {
            JSONObject video = videos.getJSONObject(i);
            JSONObject videoUrl = video == null ? null : video.getJSONObject("video_url");
            String url = videoUrl == null ? "" : safe(videoUrl.getString("url")).trim();
            if (url.isEmpty()) {
                continue;
            }
            sb.append("\u89c6\u9891\u94fe\u63a5").append(i + 1).append(": ").append(url).append("\n\n");
        }
        return sb.toString().trim();
    }

    private boolean hasCompletedVideoResult(JSONObject upstream) {
        if (upstream == null) {
            return false;
        }
        JSONArray videos = extractVideos(upstream);
        if (videos != null && !videos.isEmpty()) {
            return true;
        }
        String status = safe(upstream.getString("task_status")).trim().toLowerCase();
        return "succeed".equals(status) || "success".equals(status);
    }

    private boolean isFailedVideoTask(JSONObject upstream) {
        if (upstream == null) {
            return false;
        }
        String status = safe(upstream.getString("task_status")).trim().toLowerCase();
        return "failed".equals(status) || "fail".equals(status) || "canceled".equals(status) || "cancelled".equals(status);
    }

    private void sleepQuietly(long millis) throws IOException {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for video generation result", ex);
        }
    }

    private JSONObject imageUsage(String prompt, int imageCount) {
        return mediaUsage(prompt, imageCount);
    }

    private JSONObject mediaUsage(String prompt, int mediaCount) {
        long promptTokens = roughTokenCount(prompt);
        long completionTokens = Math.max(1L, mediaCount);
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        return usage;
    }

    private JSONObject extractUsage(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONObject usage = root.getJSONObject("usage");
        if (usage == null) {
            return null;
        }
        Long prompt = firstLong(usage, "prompt_tokens", "input_tokens", "promptTokens", "inputTokens");
        Long completion = firstLong(usage, "completion_tokens", "output_tokens", "completionTokens", "outputTokens");
        Long total = firstLong(usage, "total_tokens", "totalTokens");
        if (prompt == null && completion == null && total == null) {
            return null;
        }
        return normalizeUsage(prompt, completion, total);
    }

    private JSONObject estimateUsage(ChatCompletionRequest request, JSONObject root) {
        StringBuilder promptText = new StringBuilder();
        if (request != null && request.getMessages() != null) {
            for (ChatCompletionRequest.Message message : request.getMessages()) {
                if (message == null) {
                    continue;
                }
                promptText.append(safe(message.getRole())).append(':').append(message.getContent()).append('\n');
            }
        }

        String completionText = "";
        if (root != null) {
            JSONArray choices = root.getJSONArray("choices");
            JSONObject first = choices == null || choices.isEmpty() ? null : choices.getJSONObject(0);
            JSONObject msg = first == null ? null : first.getJSONObject("message");
            if (msg != null) {
                completionText = safe(msg.getString("content"));
            }
        }

        long prompt = roughTokenCount(promptText.toString());
        long completion = roughTokenCount(completionText);
        return normalizeUsage(prompt, completion, prompt + completion);
    }

    private long roughTokenCount(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil(text.trim().length() / 4.0d));
    }

    private JSONObject normalizeUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
        long prompt = promptTokens == null ? 0L : Math.max(promptTokens, 0L);
        long completion = completionTokens == null ? 0L : Math.max(completionTokens, 0L);
        long total = totalTokens == null || totalTokens < 0 ? prompt + completion : totalTokens;
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        usage.put("total_tokens", total);
        return usage;
    }

    private String resolveModelForUpstream(String requestModel) {
        return safeTrim(apiKeyProvider.getChatModel(), safeTrim(aiProperties.getGlm().getModel(), "glm-5.1"));
    }

    private String resolveImageModel(String requestModel) {
        String model = safe(requestModel).trim();
        if ("glm-image".equalsIgnoreCase(model)) {
            return model;
        }
        return safeTrim(aiProperties.getGlm().getImageModel(), "glm-image");
    }

    private String resolveVideoModel(String requestModel) {
        String model = safe(requestModel).trim();
        if (!model.isEmpty() && !"openclaw".equalsIgnoreCase(model) && !model.toLowerCase().startsWith("openclaw:")) {
            return model;
        }
        return safeTrim(aiProperties.getGlm().getVideoModel(), "cogvideox-flash");
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

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String lastN(String value, int n) {
        String s = safe(value).trim();
        if (s.isEmpty() || n <= 0) {
            return "";
        }
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeTrim(String value, String defaultValue) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
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
