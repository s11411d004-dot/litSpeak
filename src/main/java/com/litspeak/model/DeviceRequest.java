package com.litspeak.model;

import jakarta.validation.constraints.NotBlank;

public class DeviceRequest {
    @NotBlank(message = "device_id is required")
    private String deviceId;

    @NotBlank(message = "platform is required")
    private String platform;

    @NotBlank(message = "app_version is required")
    private String appVersion;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
}
