package com.bootdo.ai.vo;

/**
 * 登录时上报的客户端设备信息。
 */
public class AppDeviceInfo {
    private final String deviceModel;
    private final String osVersion;
    private final String appName;

    public AppDeviceInfo(String deviceModel, String osVersion, String appName) {
        this.deviceModel = deviceModel;
        this.osVersion = osVersion;
        this.appName = appName;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public String getAppName() {
        return appName;
    }
}
