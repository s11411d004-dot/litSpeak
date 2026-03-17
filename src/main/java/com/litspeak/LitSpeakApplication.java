package com.litspeak;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class LitSpeakApplication {
    public static void main(String[] args) {
        SpringApplication.run(LitSpeakApplication.class, args);
    }
}
