package com.bootdo.ai.domain;

import com.bootdo.common.utils.AESUtils;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Date;

public class AppUserDO {
    private Long id;
    private String phone;
    private String password;
    private String deviceModel;
    private String osVersion;
    private String appName;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date gmtCreate;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date gmtModified;
    private Boolean vipActive;
    private String vipExpiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @JsonIgnore
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Date getGmtCreate() {
        return gmtCreate;
    }

    public void setGmtCreate(Date gmtCreate) {
        this.gmtCreate = gmtCreate;
    }

    public Date getGmtModified() {
        return gmtModified;
    }

    public void setGmtModified(Date gmtModified) {
        this.gmtModified = gmtModified;
    }

    public Boolean getVipActive() { return vipActive; }
    public void setVipActive(Boolean vipActive) { this.vipActive = vipActive; }
    public String getVipExpiresAt() { return vipExpiresAt; }
    public void setVipExpiresAt(String vipExpiresAt) { this.vipExpiresAt = vipExpiresAt; }

    public boolean isHasPassword() {
        return password != null && !password.isEmpty();
    }

    /**
     * 解密后的密码原文，供后台管理页面查看。
     */
    public String getDecryptedPassword() {
        return password == null || password.isEmpty() ? null : AESUtils.decrypt(password);
    }
}
