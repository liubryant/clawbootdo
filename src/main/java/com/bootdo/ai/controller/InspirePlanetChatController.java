package com.bootdo.ai.controller;

import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.dto.ChatCompletionRequest;
import com.bootdo.ai.service.InspirePlanetChatService;
import com.bootdo.ai.service.AppAccessTokenService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
public class InspirePlanetChatController {
    private final InspirePlanetChatService chatService;
    private final AppAccessTokenService tokenService;
    public InspirePlanetChatController(InspirePlanetChatService chatService, AppAccessTokenService tokenService) {
        this.chatService = chatService;
        this.tokenService = tokenService;
    }

    @PostMapping("/v1/apps/inspireplanet/chat/completions")
    public void completion(@RequestBody(required = false) ChatCompletionRequest request,
                           @RequestHeader(value = "Authorization", required = false) String authorization,
                           HttpServletResponse response) throws IOException {
        if (authenticatedPhone(authorization) == null) {
            writeError(response, 401, "unauthorized", "登录状态已失效，请重新登录");
            return;
        }
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            writeError(response, 400, "invalid_request", "messages cannot be empty");
            return;
        }
        try {
            chatService.completion(request, response);
        } catch (IllegalStateException e) {
            if (!response.isCommitted()) writeError(response, 503, "service_unavailable", e.getMessage());
        } catch (IOException e) {
            if (!response.isCommitted()) writeError(response, 502, "upstream_error", e.getMessage());
        }
    }

    private String authenticatedPhone(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return tokenService.verifyAndGetPhone(authorization.substring(7).trim());
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        JSONObject body = new JSONObject();
        body.put("error", error);
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body.toJSONString());
    }
}
