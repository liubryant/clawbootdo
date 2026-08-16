package com.bootdo.ai.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bootdo.ai.dao.AppModelConfigDao;
import com.bootdo.ai.domain.AppModelConfigDO;
import com.bootdo.ai.dto.ChatCompletionRequest;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 灵感星球专用豆包文生视频适配器；不复用、不修改原有智谱视频路由。 */
@Service
public class InspirePlanetVideoService {
    private static final String APP_CODE = "inspireplanet";
    private static final String CONFIG_TYPE = "VIDEO_TEXT";
    private final AppModelConfigDao configDao;

    public InspirePlanetVideoService(AppModelConfigDao configDao) {
        this.configDao = configDao;
    }

    public JSONObject create(ChatCompletionRequest request) throws IOException {
        AppModelConfigDO config = requireConfig();
        JSONObject body = new JSONObject(true);
        body.put("model", config.getAiModel());
        JSONArray content = new JSONArray();
        JSONObject text = new JSONObject(true);
        text.put("type", "text");
        text.put("text", buildPrompt(request));
        content.add(text);
        body.put("content", content);

        JSONObject upstream = execute(config, "POST", tasksEndpoint(config), body);
        String id = upstream.getString("id");
        if (blank(id)) throw new IOException("豆包视频服务未返回任务编号");
        JSONObject result = new JSONObject(true);
        result.put("id", id);
        result.put("task_status", "PROCESSING");
        result.put("status", "queued");
        result.put("model", config.getAiModel());
        return result;
    }

    public JSONObject query(String taskId) throws IOException {
        AppModelConfigDO config = requireConfig();
        JSONObject upstream = execute(config, "GET", tasksEndpoint(config) + "/" + urlPath(taskId), null);
        String status = upstream.getString("status");
        JSONObject result = new JSONObject(true);
        result.put("id", upstream.getString("id") == null ? taskId : upstream.getString("id"));
        result.put("status", status);
        result.put("task_status", compatibleStatus(status));
        result.put("model", upstream.getString("model"));
        if ("succeeded".equalsIgnoreCase(status)) {
            JSONObject output = upstream.getJSONObject("content");
            String videoUrl = output == null ? null : output.getString("video_url");
            if (blank(videoUrl)) throw new IOException("豆包任务已完成但未返回视频地址");
            JSONObject video = new JSONObject(true);
            video.put("url", videoUrl);
            JSONArray videos = new JSONArray();
            videos.add(video);
            result.put("video_result", videos);
        } else if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
            result.put("error", upstream.get("error"));
        }
        return result;
    }

    private String buildPrompt(ChatCompletionRequest request) {
        String prompt = request.getPrompt().trim();
        String ratio = ratio(request.getSize());
        int duration = request.getDuration() == null ? 5 : Math.max(4, Math.min(15, request.getDuration()));
        boolean audio = request.getWithAudio() == null || request.getWithAudio();
        StringBuilder result = new StringBuilder(prompt);
        if (!containsFlag(prompt, "--ratio")) result.append(" --ratio ").append(ratio);
        if (!containsFlag(prompt, "--dur") && !containsFlag(prompt, "--duration"))
            result.append(" --dur ").append(duration);
        if (!containsFlag(prompt, "--generate_audio") && !containsFlag(prompt, "--generate-audio"))
            result.append(" --generate_audio ").append(audio);
        return result.toString();
    }

    private String ratio(String size) {
        if (blank(size)) return "9:16";
        String value = size.trim().toLowerCase();
        if (value.equals("720x1280") || value.equals("9:16") || value.equals("portrait")) return "9:16";
        if (value.equals("1280x720") || value.equals("16:9") || value.equals("landscape")) return "16:9";
        if (value.equals("1024x1024") || value.equals("1:1") || value.equals("square")) return "1:1";
        return "9:16";
    }

    private boolean containsFlag(String prompt, String flag) {
        return prompt.toLowerCase().contains(flag.toLowerCase());
    }

    private String compatibleStatus(String status) {
        if ("succeeded".equalsIgnoreCase(status)) return "SUCCESS";
        if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) return "FAIL";
        return "PROCESSING";
    }

    private AppModelConfigDO requireConfig() {
        AppModelConfigDO config = configDao.get(APP_CODE, CONFIG_TYPE);
        if (config == null || config.getEnabled() == null || config.getEnabled() != 1)
            throw new IllegalStateException("灵感星球文生视频服务未启用");
        if (blank(config.getAiApiKey())) throw new IllegalStateException("请先在灵感星球模型管理中配置文生视频 API Key");
        if (blank(config.getAiBaseUrl()) || blank(config.getAiModel()))
            throw new IllegalStateException("灵感星球文生视频配置不完整");
        return config;
    }

    private String tasksEndpoint(AppModelConfigDO config) {
        String base = config.getAiBaseUrl().replaceAll("/+$", "");
        String suffix = "/contents/generations/tasks";
        if (!base.endsWith(suffix)) base += suffix;
        if (!base.startsWith("https://")) throw new IllegalStateException("文生视频接口地址必须使用 HTTPS");
        return base;
    }

    private JSONObject execute(AppModelConfigDO config, String method, String endpoint, JSONObject body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + config.getAiApiKey());
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        if (status >= 400) throw new IOException(upstreamMessage(status, response));
        try { return JSON.parseObject(response); }
        catch (Exception e) { throw new IOException("豆包视频服务返回格式异常"); }
    }

    private String upstreamMessage(int status, String body) {
        try {
            JSONObject root = JSON.parseObject(body);
            JSONObject error = root.getJSONObject("error");
            String message = error == null ? root.getString("message") : error.getString("message");
            if (!blank(message)) return "豆包视频生成失败：" + message;
        } catch (Exception ignored) { }
        return "豆包视频生成失败（HTTP " + status + "）";
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

    private String urlPath(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
