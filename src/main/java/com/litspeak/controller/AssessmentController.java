package com.litspeak.controller;

import com.litspeak.model.ApiResponse;
import com.litspeak.service.AssessmentService;
import com.litspeak.service.QuotaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssessmentController {

    private final AssessmentService assessmentService;
    private final QuotaService quotaService;

    public AssessmentController(AssessmentService assessmentService, QuotaService quotaService) {
        this.assessmentService = assessmentService;
        this.quotaService = quotaService;
    }

    @PostMapping("/assessment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assess(
            @RequestHeader("X-Device-ID") String deviceId,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("reference_text") String referenceText,
            @RequestParam(value = "sentence_id", required = false) String sentenceId) throws IOException {

        if (audio.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required");
        }
        if (referenceText == null || referenceText.isBlank()) {
            throw new IllegalArgumentException("reference_text is required");
        }

        int charCount = referenceText.length();
        quotaService.checkQuota(deviceId, "assessment", charCount);

        byte[] audioData = audio.getBytes();
        Map<String, Object> result = assessmentService.assess(audioData, referenceText);

        quotaService.logUsage(deviceId, "assessment", null, charCount, "success");

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
