package com.litspeak.supabase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class SupabaseClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseClient.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String serviceKey;

    public SupabaseClient(
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${supabase.url}") String supabaseUrl,
            @Value("${supabase.service.key}") String serviceKey) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = supabaseUrl + "/rest/v1";
        this.serviceKey = serviceKey;
    }

    private Request.Builder baseRequest(String path) {
        return new Request.Builder()
                .url(baseUrl + path)
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey)
                .header("Content-Type", "application/json");
    }

    /**
     * Upsert device record. If exists, update last_seen_at.
     * Returns the device data including first_seen_at.
     */
    public Map<String, Object> upsertDevice(String deviceId, String platform, String appVersion) throws IOException {
        // Try to find existing device first
        Request getReq = baseRequest("/devices?device_id=eq." + deviceId)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response getResp = httpClient.newCall(getReq).execute()) {
            String body = getResp.body().string();
            List<Map<String, Object>> existing = objectMapper.readValue(body, new TypeReference<>() {});

            if (!existing.isEmpty()) {
                // Update last_seen_at
                String updateJson = objectMapper.writeValueAsString(Map.of(
                        "last_seen_at", "now()",
                        "app_version", appVersion
                ));
                // Use Supabase's PATCH
                Request patchReq = baseRequest("/devices?device_id=eq." + deviceId)
                        .header("Prefer", "return=representation")
                        .patch(RequestBody.create(updateJson, JSON_TYPE))
                        .build();
                try (Response patchResp = httpClient.newCall(patchReq).execute()) {
                    String patchBody = patchResp.body().string();
                    List<Map<String, Object>> updated = objectMapper.readValue(patchBody, new TypeReference<>() {});
                    return updated.isEmpty() ? existing.get(0) : updated.get(0);
                }
            } else {
                // Insert new device
                String insertJson = objectMapper.writeValueAsString(Map.of(
                        "device_id", deviceId,
                        "platform", platform,
                        "app_version", appVersion
                ));
                Request postReq = baseRequest("/devices")
                        .header("Prefer", "return=representation")
                        .post(RequestBody.create(insertJson, JSON_TYPE))
                        .build();
                try (Response postResp = httpClient.newCall(postReq).execute()) {
                    String postBody = postResp.body().string();
                    List<Map<String, Object>> inserted = objectMapper.readValue(postBody, new TypeReference<>() {});
                    return inserted.isEmpty() ? Map.of("device_id", deviceId) : inserted.get(0);
                }
            }
        }
    }

    /**
     * Check if device exists in the devices table.
     */
    public boolean deviceExists(String deviceId) throws IOException {
        Request req = baseRequest("/devices?device_id=eq." + deviceId + "&select=device_id")
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            String body = resp.body().string();
            List<?> results = objectMapper.readValue(body, List.class);
            return !results.isEmpty();
        }
    }

    /**
     * Insert a usage log entry.
     */
    public void insertUsageLog(String deviceId, String service, String contentType, int charsUsed, String status) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("device_id", deviceId);
        data.put("service", service);
        data.put("content_type", contentType);
        data.put("chars_used", charsUsed);
        data.put("status", status);

        String json = objectMapper.writeValueAsString(data);
        Request req = baseRequest("/usage_logs")
                .header("Prefer", "return=minimal")
                .post(RequestBody.create(json, JSON_TYPE))
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                log.error("Failed to insert usage log: {} - {}", resp.code(), resp.body().string());
            }
        }
    }

    /**
     * Get the total chars used for a device+service in a given month.
     * Uses Supabase's filtering: timestamp >= start of month AND timestamp < start of next month
     * AND status = 'success'
     */
    public int getMonthlyUsage(String deviceId, String service, YearMonth yearMonth) throws IOException {
        String startDate = yearMonth.atDay(1).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        String endDate = yearMonth.plusMonths(1).atDay(1).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

        String path = "/usage_logs?device_id=eq." + deviceId
                + "&service=eq." + service
                + "&status=eq.success"
                + "&timestamp=gte." + startDate
                + "&timestamp=lt." + endDate
                + "&select=chars_used";

        Request req = baseRequest(path)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            String body = resp.body().string();
            List<Map<String, Object>> results = objectMapper.readValue(body, new TypeReference<>() {});
            return results.stream()
                    .mapToInt(r -> ((Number) r.get("chars_used")).intValue())
                    .sum();
        }
    }

    /**
     * Get published content from official_content table.
     */
    public List<Map<String, Object>> getPublishedContent(String type) throws IOException {
        String path = "/official_content?is_published=eq.true&select=*";
        if (type != null && !type.isBlank()) {
            path += "&type=eq." + type;
        }
        path += "&order=created_at.desc";

        Request req = baseRequest(path)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            String body = resp.body().string();
            List<Map<String, Object>> results = objectMapper.readValue(body, new TypeReference<>() {});
            results.forEach(r -> r.remove("is_published"));
            return results;
        }
    }
}
