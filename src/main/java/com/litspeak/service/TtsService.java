package com.litspeak.service;

import com.litspeak.exception.ServiceUnavailableException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class TtsService {
    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final OkHttpClient httpClient;
    private final String speechKey;
    private final String region;

    public TtsService(
            OkHttpClient httpClient,
            @Value("${azure.speech.key}") String speechKey,
            @Value("${azure.region}") String region) {
        this.httpClient = httpClient;
        this.speechKey = speechKey;
        this.region = region;
    }

    public byte[] synthesize(String text, String voiceCode, int speed) {
        String ssml = buildSsml(text, voiceCode, speed);
        String url = "https://" + region + ".tts.speech.microsoft.com/cognitiveservices/v1";

        Request request = new Request.Builder()
                .url(url)
                .header("Ocp-Apim-Subscription-Key", speechKey)
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
                .post(RequestBody.create(ssml, MediaType.parse("application/ssml+xml")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                log.error("Azure TTS failed: {} - {}", response.code(), errorBody);
                throw new ServiceUnavailableException("TTS", new RuntimeException("Azure TTS returned " + response.code()));
            }
            return response.body().bytes();
        } catch (IOException e) {
            log.error("Azure TTS request failed", e);
            throw new ServiceUnavailableException("TTS", e);
        }
    }

    private String buildSsml(String text, String voiceCode, int speed) {
        // Escape XML special characters in text
        String escapedText = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");

        String rateStr = speed >= 0 ? "+" + speed + "%" : speed + "%";
        // Extract language from voice_code: en-US-JennyNeural -> en-US, zh-TW-HsiaoChenNeural -> zh-TW
        String[] parts = voiceCode.split("-");
        String lang = parts.length >= 2 ? parts[0] + "-" + parts[1] : "en-US";

        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + lang + "'>"
                + "<voice name='" + voiceCode + "'>"
                + "<prosody rate='" + rateStr + "'>"
                + escapedText
                + "</prosody></voice></speak>";
    }
}
