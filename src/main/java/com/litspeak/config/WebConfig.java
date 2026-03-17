package com.litspeak.config;

import com.litspeak.middleware.DeviceIdInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private DeviceIdInterceptor deviceIdInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deviceIdInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/devices", "/api/health");
    }
}
