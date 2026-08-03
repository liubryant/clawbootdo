package com.bootdo.ai.domain;

public class AppModelConfigDO {
    private Long id;
    private String appCode;
    private String configType;
    private String aiProvider;
    private String aiBaseUrl;
    private String aiApiKey;
    private String aiModel;
    private Integer enabled;
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppCode() { return appCode; }
    public void setAppCode(String appCode) { this.appCode = trim(appCode); }
    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = trim(configType); }
    public String getAiProvider() { return aiProvider; }
    public void setAiProvider(String aiProvider) { this.aiProvider = trim(aiProvider); }
    public String getAiBaseUrl() { return aiBaseUrl; }
    public void setAiBaseUrl(String aiBaseUrl) { this.aiBaseUrl = trim(aiBaseUrl); }
    public String getAiApiKey() { return aiApiKey; }
    public void setAiApiKey(String aiApiKey) { this.aiApiKey = trim(aiApiKey); }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = trim(aiModel); }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = trim(note); }
    private String trim(String value) { return value == null ? null : value.trim(); }
}
