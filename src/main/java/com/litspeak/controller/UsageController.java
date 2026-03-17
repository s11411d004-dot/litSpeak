package com.litspeak.controller;

import com.litspeak.model.ApiResponse;
import com.litspeak.service.QuotaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class UsageController {

    private final QuotaService quotaService;

    public UsageController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUsage(
            @RequestHeader("X-Device-ID") String deviceId) {

        Map<String, Object> usage = quotaService.getUsageSummary(deviceId);
        return ResponseEntity.ok(ApiResponse.success(usage));
    }
}
