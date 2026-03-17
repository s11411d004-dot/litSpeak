package com.litspeak.controller;

import com.litspeak.model.ApiResponse;
import com.litspeak.model.TranslateRequest;
import com.litspeak.service.QuotaService;
import com.litspeak.service.TranslateService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TranslateController {

    private final TranslateService translateService;
    private final QuotaService quotaService;

    public TranslateController(TranslateService translateService, QuotaService quotaService) {
        this.translateService = translateService;
        this.quotaService = quotaService;
    }

    @PostMapping("/translate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> translate(
            @RequestHeader("X-Device-ID") String deviceId,
            @Valid @RequestBody TranslateRequest request) {

        int totalChars = request.getSentences().stream().mapToInt(String::length).sum();

        // Check quota before calling Azure
        quotaService.checkQuota(deviceId, "translate", totalChars);

        List<String> translations = translateService.translate(request.getSentences(), request.getTargetLang());

        // Log successful usage
        quotaService.logUsage(deviceId, "translate", null, totalChars, "success");

        return ResponseEntity.ok(ApiResponse.success(Map.of("translations", translations)));
    }
}
