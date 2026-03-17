package com.litspeak.controller;

import com.litspeak.model.ApiResponse;
import com.litspeak.service.ContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/content-library")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getContentLibrary(
            @RequestHeader("X-Device-ID") String deviceId,
            @RequestParam(value = "type", required = false) String type) {

        List<Map<String, Object>> items = contentService.getPublishedContent(type);
        return ResponseEntity.ok(ApiResponse.success(Map.of("items", items)));
    }
}
