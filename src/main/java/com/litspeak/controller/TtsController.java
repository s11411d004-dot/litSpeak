package com.litspeak.controller;

import com.litspeak.model.TtsRequest;
import com.litspeak.service.QuotaService;
import com.litspeak.service.TtsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TtsController {

    private final TtsService ttsService;
    private final QuotaService quotaService;

    public TtsController(TtsService ttsService, QuotaService quotaService) {
        this.ttsService = ttsService;
        this.quotaService = quotaService;
    }

    @PostMapping("/tts")
    public ResponseEntity<byte[]> tts(
            @RequestHeader("X-Device-ID") String deviceId,
            @Valid @RequestBody TtsRequest request) {

        int charCount = request.getText().length();

        // Check quota before calling Azure
        quotaService.checkQuota(deviceId, "tts", charCount);

        byte[] audioData = ttsService.synthesize(request.getText(), request.getVoiceCode(), request.getSpeed());

        // Log successful usage
        quotaService.logUsage(deviceId, "tts", null, charCount, "success");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.setContentLength(audioData.length);

        return ResponseEntity.ok().headers(headers).body(audioData);
    }
}
