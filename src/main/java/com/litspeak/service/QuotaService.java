package com.litspeak.service;

import com.litspeak.exception.QuotaExceededException;
import com.litspeak.supabase.SupabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.YearMonth;
import java.util.Map;

@Service
public class QuotaService {
    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final SupabaseClient supabaseClient;
    private final int ttsMonthlyLimit;
    private final int translateMonthlyLimit;
    private final int assessmentMonthlyLimit;

    public QuotaService(
            SupabaseClient supabaseClient,
            @Value("${quota.tts.monthly}") int ttsMonthlyLimit,
            @Value("${quota.translate.monthly}") int translateMonthlyLimit,
            @Value("${quota.assessment.monthly}") int assessmentMonthlyLimit) {
        this.supabaseClient = supabaseClient;
        this.ttsMonthlyLimit = ttsMonthlyLimit;
        this.translateMonthlyLimit = translateMonthlyLimit;
        this.assessmentMonthlyLimit = assessmentMonthlyLimit;
    }

    public void checkQuota(String deviceId, String service, int additionalChars) {
        int currentUsage = getMonthlyUsage(deviceId, service);
        int limit = getLimit(service);
        if (currentUsage + additionalChars > limit) {
            throw new QuotaExceededException(service);
        }
    }

    @Cacheable(value = "quotas", key = "#deviceId + '_' + #service")
    public int getMonthlyUsage(String deviceId, String service) {
        try {
            return supabaseClient.getMonthlyUsage(deviceId, service, YearMonth.now());
        } catch (IOException e) {
            log.error("Failed to get monthly usage for device {} service {}", deviceId, service, e);
            return 0;
        }
    }

    public Map<String, Object> getUsageSummary(String deviceId) {
        int ttsUsage = getMonthlyUsage(deviceId, "tts");
        int translateUsage = getMonthlyUsage(deviceId, "translate");
        int assessmentUsage = getMonthlyUsage(deviceId, "assessment");
        YearMonth current = YearMonth.now();
        return Map.of(
                "tts_chars_used", ttsUsage,
                "tts_monthly_limit", ttsMonthlyLimit,
                "translate_chars_used", translateUsage,
                "translate_monthly_limit", translateMonthlyLimit,
                "assessment_chars_used", assessmentUsage,
                "assessment_monthly_limit", assessmentMonthlyLimit,
                "month", current.toString()
        );
    }

    private int getLimit(String service) {
        return switch (service) {
            case "tts" -> ttsMonthlyLimit;
            case "translate" -> translateMonthlyLimit;
            case "assessment" -> assessmentMonthlyLimit;
            default -> 0;
        };
    }

    public void logUsage(String deviceId, String service, String contentType, int charsUsed, String status) {
        try {
            supabaseClient.insertUsageLog(deviceId, service, contentType, charsUsed, status);
        } catch (IOException e) {
            log.error("Failed to log usage for device {} service {}", deviceId, service, e);
        }
    }
}
