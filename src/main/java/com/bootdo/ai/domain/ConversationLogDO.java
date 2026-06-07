package com.bootdo.ai.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

public class ConversationLogDO {
    private Long id;
    private String deviceName;
    private String requestIp;
    private String conversationType;
    private String conversationContent;
    private String conversationResult;
    private String model;
    private Integer stream;
    private Integer success;
    private Integer elapsedMs;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date gmtCreate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = trim(deviceName);
    }

    public String getRequestIp() {
        return requestIp;
    }

    public void setRequestIp(String requestIp) {
        this.requestIp = trim(requestIp);
    }

    public String getConversationType() {
        return conversationType;
    }

    public void setConversationType(String conversationType) {
        this.conversationType = trim(conversationType);
    }

    public String getConversationContent() {
        return conversationContent;
    }

    public void setConversationContent(String conversationContent) {
        this.conversationContent = trim(conversationContent);
    }

    public String getConversationResult() {
        return conversationResult;
    }

    public void setConversationResult(String conversationResult) {
        this.conversationResult = trim(conversationResult);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = trim(model);
    }

    public Integer getStream() {
        return stream;
    }

    public void setStream(Integer stream) {
        this.stream = stream;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public Integer getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Integer elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public Date getGmtCreate() {
        return gmtCreate;
    }

    public void setGmtCreate(Date gmtCreate) {
        this.gmtCreate = gmtCreate;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
