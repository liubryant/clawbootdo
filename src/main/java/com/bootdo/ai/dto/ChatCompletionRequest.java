package com.bootdo.ai.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatCompletionRequest {
    private String model;
    private Boolean stream;
    private Double temperature;
    private String prompt;
    private String quality;
    @JsonProperty("with_audio")
    private Boolean withAudio;
    private String size;
    private Integer fps;
    private Integer duration;
    private String deviceName;
    private String deviceId;
    private String clientId;
    private String sessionId;
    private Map<String, Object> extras = new HashMap<>();
    private List<Message> messages = new ArrayList<>();
    private List<Map<String, Object>> tools = new ArrayList<>();
    @JsonProperty("tool_choice")
    private Object toolChoice;
    private String chatEntryMode;
    private String responseMode;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public Boolean getWithAudio() {
        return withAudio;
    }

    public void setWithAudio(Boolean withAudio) {
        this.withAudio = withAudio;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public Integer getFps() {
        return fps;
    }

    public void setFps(Integer fps) {
        this.fps = fps;
    }

    public Integer getDuration() { return duration; }

    public void setDuration(Integer duration) { this.duration = duration; }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getExtraString(String key) {
        Object value = extras.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @JsonAnySetter
    public void setExtra(String key, Object value) {
        extras.put(key, value);
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public List<Map<String, Object>> getTools() {
        return tools;
    }

    public void setTools(List<Map<String, Object>> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public String getChatEntryMode() {
        return chatEntryMode;
    }

    public void setChatEntryMode(String chatEntryMode) {
        this.chatEntryMode = chatEntryMode;
    }

    public String getResponseMode() {
        return responseMode;
    }

    public void setResponseMode(String responseMode) {
        this.responseMode = responseMode;
    }

    public static class Message {
        private String role;
        private Object content;
        private String name;
        @JsonProperty("tool_call_id")
        private String toolCallId;
        @JsonProperty("tool_calls")
        private List<Map<String, Object>> toolCalls = new ArrayList<>();

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public Object getContent() {
            return content;
        }

        public void setContent(Object content) {
            this.content = content;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public void setToolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
        }

        public List<Map<String, Object>> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<Map<String, Object>> toolCalls) {
            this.toolCalls = toolCalls;
        }
    }
}
