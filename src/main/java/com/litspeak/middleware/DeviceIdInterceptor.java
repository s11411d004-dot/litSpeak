package com.litspeak.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litspeak.model.ApiResponse;
import com.litspeak.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

@Component
public class DeviceIdInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(DeviceIdInterceptor.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;

    public DeviceIdInterceptor(DeviceService deviceService, ObjectMapper objectMapper) {
        this.deviceService = deviceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String deviceId = request.getHeader("X-Device-ID");

        if (deviceId == null || deviceId.isBlank()) {
            writeError(response, 400, "INVALID_REQUEST", "X-Device-ID header is required");
            return false;
        }

        if (!UUID_PATTERN.matcher(deviceId).matches()) {
            writeError(response, 400, "INVALID_REQUEST", "X-Device-ID must be a valid UUID");
            return false;
        }

        if (!deviceService.deviceExists(deviceId)) {
            writeError(response, 401, "UNAUTHORIZED", "Device not registered");
            return false;
        }

        return true;
    }

    private void writeError(HttpServletResponse response, int status, String error, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(error, message)));
    }
}
