package com.litspeak.controller;

import com.litspeak.model.ApiResponse;
import com.litspeak.model.DeviceRequest;
import com.litspeak.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/devices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerDevice(@Valid @RequestBody DeviceRequest request) throws IOException {
        Map<String, Object> result = deviceService.registerDevice(
                request.getDeviceId(),
                request.getPlatform(),
                request.getAppVersion()
        );
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "device_id", result.getOrDefault("device_id", request.getDeviceId()),
                "first_seen_at", result.getOrDefault("first_seen_at", "")
        )));
    }
}
