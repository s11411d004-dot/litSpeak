package com.litspeak.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Arrays.asList(
            new CaffeineCache("deviceIds",
                Caffeine.newBuilder()
                    .maximumSize(10000)
                    .expireAfterWrite(60, TimeUnit.MINUTES)
                    .build()),
            new CaffeineCache("quotas",
                Caffeine.newBuilder()
                    .maximumSize(10000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build())
        ));
        return cacheManager;
    }
}
