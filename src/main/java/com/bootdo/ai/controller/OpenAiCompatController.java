package com.bootdo.ai.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.config.AiProperties;
import com.bootdo.ai.domain.ConversationLogDO;
import com.bootdo.ai.dto.ChatCompletionRequest;
import com.bootdo.ai.service.ConversationLogService;
import com.bootdo.ai.service.GlmChatService;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
public class OpenAiCompatController {

    private final GlmChatService glmChatService;
    private final AiProperties aiProperties;
    private final ConversationLogService conversationLogService;

    public OpenAiCompatController(GlmChatService glmChatService,
                                  AiProperties aiProperties,
                                  ConversationLogService conversationLogService) {
        this.glmChatService = glmChatService;
        this.aiProperties = aiProperties;
        this.conversationLogService = conversationLogService;
    }

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(@RequestBody(required = false) ChatCompletionRequest request,
                                HttpServletRequest httpRequest,
                                HttpServletResponse response) throws IOException {
        if (!aiProperties.isEnabled()) {
            writeJson(response, 503, error("service_disabled", "AI compatibility API is disabled"));
            return;
        }

        if (request == null) {
            writeJson(response, 400, error("invalid_request", "request body is empty or not valid json"));
            return;
        }

        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            writeJson(response, 400, error("invalid_request", "messages cannot be empty"));
            return;
        }

        String expectedToken = safeTrim(aiProperties.getGatewayToken());
        if (!expectedToken.isEmpty()) {
            String auth = safeTrim(httpRequest.getHeader("Authorization"));
            String prefix = "Bearer ";
            if (!auth.startsWith(prefix) || !expectedToken.equals(auth.substring(prefix.length()).trim())) {
                writeJson(response, 401, error("unauthorized", "invalid gateway token"));
                return;
            }
        }

        boolean stream = request.getStream() == null || request.getStream();
        long start = System.currentTimeMillis();
        String result = "";
        boolean success = false;
        try {
            if (stream) {
                glmChatService.streamCompletion(request, response);
                result = "流式响应已完成";
            } else {
                result = glmChatService.nonStreamCompletion(request, response);
            }
            success = true;
        } catch (IOException ex) {
            result = "异常：" + ex.getMessage();
            throw ex;
        } catch (RuntimeException ex) {
            result = "异常：" + ex.getMessage();
            throw ex;
        } finally {
            saveConversationLog(httpRequest, request, detectConversationType(request), stream, success, result, start);
        }
    }

    @PostMapping("/v1/videos/generations")
    public void videoGenerations(@RequestBody(required = false) ChatCompletionRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse response) throws IOException {
        if (!aiProperties.isEnabled()) {
            writeJson(response, 503, error("service_disabled", "AI compatibility API is disabled"));
            return;
        }

        if (request == null) {
            writeJson(response, 400, error("invalid_request", "request body is empty or not valid json"));
            return;
        }

        if ((request.getMessages() == null || request.getMessages().isEmpty())
            && safeTrim(request.getPrompt()).isEmpty()) {
            writeJson(response, 400, error("invalid_request", "messages or prompt cannot be empty"));
            return;
        }

        String expectedToken = safeTrim(aiProperties.getGatewayToken());
        if (!expectedToken.isEmpty()) {
            String auth = safeTrim(httpRequest.getHeader("Authorization"));
            String prefix = "Bearer ";
            if (!auth.startsWith(prefix) || !expectedToken.equals(auth.substring(prefix.length()).trim())) {
                writeJson(response, 401, error("unauthorized", "invalid gateway token"));
                return;
            }
        }

        long start = System.currentTimeMillis();
        String result = "";
        boolean success = false;
        try {
            result = glmChatService.videoGenerations(request, response);
            success = true;
        } catch (IOException ex) {
            result = "异常：" + ex.getMessage();
            throw ex;
        } catch (RuntimeException ex) {
            result = "异常：" + ex.getMessage();
            throw ex;
        } finally {
            saveConversationLog(httpRequest, request, "VIDEO", false, success, result, start);
        }
    }

    @PostMapping("/v1/images/edits")
    public void imageEdits(@RequestBody(required = false) ChatCompletionRequest request,
                           HttpServletRequest httpRequest,
                           HttpServletResponse response) throws IOException {
        if (!aiProperties.isEnabled()) {
            writeJson(response, 503, error("service_disabled", "AI compatibility API is disabled"));
            return;
        }

        if (request == null) {
            writeJson(response, 400, error("invalid_request", "request body is empty or not valid json"));
            return;
        }

        if ((request.getMessages() == null || request.getMessages().isEmpty())
            && safeTrim(request.getPrompt()).isEmpty()) {
            writeJson(response, 400, error("invalid_request", "messages or prompt cannot be empty"));
            return;
        }

        String expectedToken = safeTrim(aiProperties.getGatewayToken());
        if (!expectedToken.isEmpty()) {
            String auth = safeTrim(httpRequest.getHeader("Authorization"));
            String prefix = "Bearer ";
            if (!auth.startsWith(prefix) || !expectedToken.equals(auth.substring(prefix.length()).trim())) {
                writeJson(response, 401, error("unauthorized", "invalid gateway token"));
                return;
            }
        }

        long start = System.currentTimeMillis();
        String result = "";
        boolean success = false;
        try {
            result = glmChatService.imageEdits(request, response);
            success = true;
        } catch (IllegalArgumentException ex) {
            result = "异常：" + ex.getMessage();
            writeJson(response, 400, error("invalid_request", ex.getMessage()));
        } catch (IOException ex) {
            result = "异常：" + ex.getMessage();
            if (!response.isCommitted()) {
                writeJson(response, 502, error("upstream_error", ex.getMessage()));
            }
        } catch (RuntimeException ex) {
            result = "异常：" + ex.getMessage();
            if (!response.isCommitted()) {
                writeJson(response, 500, error("internal_error", ex.getMessage()));
            }
        } finally {
            saveConversationLog(httpRequest, request, "IMAGE_EDIT", false, success, result, start);
        }
    }

    @GetMapping("/v1/videos/generations/{taskId}")
    public void videoGenerationResult(@PathVariable("taskId") String taskId,
                                      HttpServletRequest httpRequest,
                                      HttpServletResponse response) throws IOException {
        if (!aiProperties.isEnabled()) {
            writeJson(response, 503, error("service_disabled", "AI compatibility API is disabled"));
            return;
        }

        if (safeTrim(taskId).isEmpty()) {
            writeJson(response, 400, error("invalid_request", "taskId cannot be empty"));
            return;
        }

        String expectedToken = safeTrim(aiProperties.getGatewayToken());
        if (!expectedToken.isEmpty()) {
            String auth = safeTrim(httpRequest.getHeader("Authorization"));
            String prefix = "Bearer ";
            if (!auth.startsWith(prefix) || !expectedToken.equals(auth.substring(prefix.length()).trim())) {
                writeJson(response, 401, error("unauthorized", "invalid gateway token"));
                return;
            }
        }

        long start = System.currentTimeMillis();
        String result = "";
        boolean success = false;
        try {
            result = glmChatService.videoGenerationResult(taskId, response);
            success = true;
        } catch (IOException ex) {
            result = "异常：" + ex.getMessage();
            throw ex;
        } catch (RuntimeException ex) {
            result = "异常：" + ex.getMessage();
            throw ex;
        } finally {
            ChatCompletionRequest logRequest = new ChatCompletionRequest();
            logRequest.setPrompt("查询视频任务：" + taskId);
            saveConversationLog(httpRequest, logRequest, "VIDEO", false, success, result, start);
        }
    }

    @PostMapping("/v1/token/usage")
    public void tokenUsage(@RequestBody(required = false) ChatCompletionRequest request,
                           HttpServletRequest httpRequest,
                           HttpServletResponse response) throws IOException {
        if (!aiProperties.isEnabled()) {
            writeJson(response, 503, error("service_disabled", "AI compatibility API is disabled"));
            return;
        }

        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            writeJson(response, 400, error("invalid_request", "messages cannot be empty"));
            return;
        }

        String expectedToken = safeTrim(aiProperties.getGatewayToken());
        if (!expectedToken.isEmpty()) {
            String auth = safeTrim(httpRequest.getHeader("Authorization"));
            String prefix = "Bearer ";
            if (!auth.startsWith(prefix) || !expectedToken.equals(auth.substring(prefix.length()).trim())) {
                writeJson(response, 401, error("unauthorized", "invalid gateway token"));
                return;
            }
        }

        try {
            JSONObject usage = glmChatService.buildTokenUsage(request);
            writeJson(response, 200, usage == null ? new JSONObject() : usage);
        } catch (IOException ex) {
            writeJson(response, 502, error("upstream_error", ex.getMessage()));
        }
    }

    private void writeJson(HttpServletResponse response, int status, JSONObject json) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.toJSONString());
    }

    private JSONObject error(String code, String message) {
        JSONObject err = new JSONObject();
        err.put("code", code);
        err.put("message", message);
        JSONObject root = new JSONObject();
        root.put("error", err);
        return root;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private void saveConversationLog(HttpServletRequest httpRequest,
                                     ChatCompletionRequest request,
                                     String conversationType,
                                     boolean stream,
                                     boolean success,
                                     String result,
                                     long start) {
        ConversationLogDO log = new ConversationLogDO();
        String requestIp = resolveRequestIp(httpRequest);
        log.setDeviceName(resolveDeviceName(httpRequest, request, requestIp));
        log.setRequestIp(requestIp);
        log.setConversationType(conversationType);
        log.setConversationContent(clip(resolveConversationContent(request), 8000));
        log.setConversationResult(clip(result, 8000));
        log.setModel(request == null ? null : request.getModel());
        log.setStream(stream ? 1 : 0);
        log.setSuccess(success ? 1 : 0);
        log.setElapsedMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, System.currentTimeMillis() - start)));
        log.setGmtCreate(new Date());
        conversationLogService.save(log);
    }

    private String detectConversationType(ChatCompletionRequest request) {
        if (request == null) {
            return "TEXT";
        }
        String responseMode = safeTrim(request.getResponseMode()).toLowerCase();
        if ("video_only".equals(responseMode)) {
            return "VIDEO";
        }
        if ("image_only".equals(responseMode)) {
            return "IMAGE";
        }
        String model = safeTrim(request.getModel());
        if ("glm-image".equalsIgnoreCase(model)) {
            return "IMAGE";
        }
        return "TEXT";
    }

    private String resolveConversationContent(ChatCompletionRequest request) {
        if (request == null) {
            return "";
        }
        String prompt = safeTrim(request.getPrompt());
        if (!prompt.isEmpty()) {
            return "1. " + prompt;
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int index = appendMessages(builder, request.getMessages(), true);
        if (index == 0) {
            appendMessages(builder, request.getMessages(), false);
        }
        return builder.toString();
    }

    private int appendMessages(StringBuilder builder, List<ChatCompletionRequest.Message> messages, boolean userOnly) {
        int index = 0;
        for (ChatCompletionRequest.Message message : messages) {
            if (message == null) {
                continue;
            }
            String role = safeTrim(message.getRole());
            if (userOnly && !"user".equalsIgnoreCase(role)) {
                continue;
            }
            Object content = message.getContent();
            if (content == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            index++;
            builder.append(index).append(". ");
            if (!role.isEmpty()) {
                builder.append(role).append(": ");
            }
            builder.append(contentToText(content));
        }
        return index;
    }

    private String contentToText(Object content) {
        if (content instanceof String) {
            return safeTrim((String) content);
        }
        if (content instanceof List) {
            StringBuilder builder = new StringBuilder();
            List<?> blocks = (List<?>) content;
            for (Object block : blocks) {
                if (block == null) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(blockToText(block));
            }
            return builder.toString();
        }
        return JSON.toJSONString(content);
    }

    private String blockToText(Object block) {
        if (!(block instanceof Map)) {
            return JSON.toJSONString(block);
        }
        Map<?, ?> map = (Map<?, ?>) block;
        Object type = map.get("type");
        Object text = map.get("text");
        if (text != null) {
            return safeTrim(String.valueOf(type)) + ": " + text;
        }
        Object imageUrl = map.get("image_url");
        if (imageUrl != null) {
            return safeTrim(String.valueOf(type)) + ": " + JSON.toJSONString(imageUrl);
        }
        return JSON.toJSONString(block);
    }

    private String resolveDeviceName(HttpServletRequest httpRequest,
                                     ChatCompletionRequest request,
                                     String requestIp) {
        String value = firstNotEmpty(
                httpRequest.getHeader("X-Device-Name"),
                httpRequest.getHeader("Device-Name"),
                httpRequest.getHeader("X-Device-ID"),
                httpRequest.getHeader("X-Device-Model"),
                httpRequest.getHeader("X-Client-Device"),
                httpRequest.getHeader("X-Client-ID"),
                httpRequest.getHeader("X-Session-ID"),
                resolveRequestDeviceName(request),
                resolveSenderDeviceName(request),
                requestIp);
        return clip(safeTrim(value), 128);
    }

    private String resolveRequestDeviceName(ChatCompletionRequest request) {
        if (request == null) {
            return "";
        }
        return firstNotEmpty(
                request.getDeviceName(),
                request.getDeviceId(),
                request.getClientId(),
                request.getSessionId(),
                request.getExtraString("device_name"),
                request.getExtraString("device"),
                request.getExtraString("device_id"),
                request.getExtraString("client_id"),
                request.getExtraString("session_id"),
                request.getExtraString("sessionKey"),
                request.getExtraString("session_key"));
    }

    private String resolveSenderDeviceName(ChatCompletionRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message message = request.getMessages().get(i);
            if (message == null || !"user".equalsIgnoreCase(safeTrim(message.getRole()))) {
                continue;
            }
            String metadata = extractSenderMetadata(contentToText(message.getContent()));
            if (!metadata.isEmpty()) {
                return metadata;
            }
        }
        return "";
    }

    private String extractSenderMetadata(String text) {
        String safe = safeTrim(text);
        int marker = safe.indexOf("Sender (untrusted metadata):");
        if (marker < 0) {
            return "";
        }
        int jsonStart = safe.indexOf('{', marker);
        if (jsonStart < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = jsonStart; i < safe.length(); i++) {
            char ch = safe.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return senderMetadataName(safe.substring(jsonStart, i + 1));
                }
            }
        }
        return "";
    }

    private String senderMetadataName(String json) {
        try {
            JSONObject metadata = JSON.parseObject(json);
            return firstNotEmpty(
                    metadata.getString("deviceName"),
                    metadata.getString("device_name"),
                    metadata.getString("deviceId"),
                    metadata.getString("device_id"),
                    metadata.getString("sessionKey"),
                    metadata.getString("session_key"),
                    metadata.getString("sessionId"),
                    metadata.getString("session_id"),
                    metadata.getString("clientId"),
                    metadata.getString("client_id"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String resolveRequestIp(HttpServletRequest request) {
        String ip = firstNotEmpty(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr());
        if (ip != null && ip.contains(",")) {
            ip = ip.substring(0, ip.indexOf(","));
        }
        return clip(safeTrim(ip), 64);
    }

    private String firstNotEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!safeTrim(value).isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String clip(String value, int maxLength) {
        String safe = value == null ? "" : value;
        if (maxLength <= 0 || safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, maxLength);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void handleNotReadable(HttpServletResponse response, HttpMessageNotReadableException ex) throws IOException {
        writeJson(response, 400, error("invalid_json", "invalid json body: " + ex.getMostSpecificCause().getMessage()));
    }
}
