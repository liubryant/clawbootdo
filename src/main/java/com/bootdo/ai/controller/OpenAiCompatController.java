package com.bootdo.ai.controller;

import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.config.AiProperties;
import com.bootdo.ai.dto.ChatCompletionRequest;
import com.bootdo.ai.service.GlmChatService;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
public class OpenAiCompatController {

    private final GlmChatService glmChatService;
    private final AiProperties aiProperties;

    public OpenAiCompatController(GlmChatService glmChatService, AiProperties aiProperties) {
        this.glmChatService = glmChatService;
        this.aiProperties = aiProperties;
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
        if (stream) {
            glmChatService.streamCompletion(request, response);
        } else {
            glmChatService.nonStreamCompletion(request, response);
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void handleNotReadable(HttpServletResponse response, HttpMessageNotReadableException ex) throws IOException {
        writeJson(response, 400, error("invalid_json", "invalid json body: " + ex.getMostSpecificCause().getMessage()));
    }
}
