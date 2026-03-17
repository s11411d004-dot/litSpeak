package com.litspeak.service;

import com.litspeak.supabase.SupabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private final SupabaseClient supabaseClient;

    public DeviceService(SupabaseClient supabaseClient) {
        this.supabaseClient = supabaseClient;
    }

    public Map<String, Object> registerDevice(String deviceId, String platform, String appVersion) throws IOException {
        return supabaseClient.upsertDevice(deviceId, platform, appVersion);
    }

    @Cacheable(value = "deviceIds", key = "#deviceId")
    public boolean deviceExists(String deviceId) {
        try {
            return supabaseClient.deviceExists(deviceId);
        } catch (IOException e) {
            log.error("Failed to check device existence: {}", deviceId, e);
            return false;
        }
    }
}
