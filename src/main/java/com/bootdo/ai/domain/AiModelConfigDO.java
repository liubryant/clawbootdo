package com.bootdo.ai.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

public class AiModelConfigDO {

    private Long id;
    /** TEXT | IMAGE | VIDEO | IMAGE_EDIT */
    private String configType;
    private String aiProvider;
    private String aiBaseUrl;
    private String aiApiKey;
    private String aiModel;
    private Integer enabled;
    private String note;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date gmtCreate;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date gmtModified;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = configType == null ? null : configType.trim(); }

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

    public Date getGmtCreate() { return gmtCreate; }
    public void setGmtCreate(Date gmtCreate) { this.gmtCreate = gmtCreate; }

    public Date getGmtModified() { return gmtModified; }
    public void setGmtModified(Date gmtModified) { this.gmtModified = gmtModified; }

    private String trim(String v) { return v == null ? null : v.trim(); }
}
