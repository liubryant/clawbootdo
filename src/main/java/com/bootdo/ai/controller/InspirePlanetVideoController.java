package com.bootdo.ai.controller;

import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.dto.ChatCompletionRequest;
import com.bootdo.ai.service.AppAccessTokenService;
import com.bootdo.ai.service.InspirePlanetVideoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
public class InspirePlanetVideoController {
    private final InspirePlanetVideoService videoService;
    private final AppAccessTokenService tokenService;

    public InspirePlanetVideoController(InspirePlanetVideoService videoService, AppAccessTokenService tokenService) {
        this.videoService = videoService;
        this.tokenService = tokenService;
    }

    @PostMapping("/v1/apps/inspireplanet/videos/generations")
    public void create(@RequestBody(required = false) ChatCompletionRequest request,
                       @RequestHeader(value = "Authorization", required = false) String authorization,
                       HttpServletResponse response) throws IOException {
        if (!authenticated(authorization)) {
            writeError(response, 401, "unauthorized", "请先登录后再生成视频");
            return;
        }
        if (request == null || blank(request.getPrompt())) {
            writeError(response, 400, "invalid_request", "prompt cannot be empty");
            return;
        }
        try { writeJson(response, 200, videoService.create(request)); }
        catch (IllegalStateException e) { writeError(response, 503, "service_unavailable", e.getMessage()); }
        catch (IOException e) { writeError(response, 502, "upstream_error", e.getMessage()); }
    }

    @GetMapping("/v1/apps/inspireplanet/videos/generations/{taskId}")
    public void query(@PathVariable String taskId,
                      @RequestHeader(value = "Authorization", required = false) String authorization,
                      HttpServletResponse response) throws IOException {
        if (!authenticated(authorization)) {
            writeError(response, 401, "unauthorized", "登录状态已失效，请重新登录");
            return;
        }
        if (blank(taskId)) {
            writeError(response, 400, "invalid_request", "taskId cannot be empty");
            return;
        }
        try { writeJson(response, 200, videoService.query(taskId)); }
        catch (IllegalStateException e) { writeError(response, 503, "service_unavailable", e.getMessage()); }
        catch (IOException e) { writeError(response, 502, "upstream_error", e.getMessage()); }
    }

    private boolean authenticated(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                && tokenService.verifyAndGetPhone(authorization.substring(7).trim()) != null;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        JSONObject error = new JSONObject(true);
        error.put("code", code);
        error.put("message", message);
        JSONObject root = new JSONObject(true);
        root.put("error", error);
        writeJson(response, status, root);
    }

    private void writeJson(HttpServletResponse response, int status, JSONObject body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body.toJSONString());
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
