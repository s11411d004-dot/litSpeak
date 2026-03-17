package com.litspeak.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litspeak.exception.ServiceUnavailableException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TranslateService {
    private static final Logger log = LoggerFactory.getLogger(TranslateService.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String translatorKey;
    private final String translatorRegion;

    public TranslateService(
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${azure.translator.key}") String translatorKey,
            @Value("${azure.translator.region}") String translatorRegion) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.translatorKey = translatorKey;
        this.translatorRegion = translatorRegion;
    }

    public List<String> translate(List<String> sentences, String targetLang) {
        try {
            // Build Azure Translator request body: [{"Text": "..."}, ...]
            List<Map<String, String>> requestBody = sentences.stream()
                    .map(s -> Map.of("Text", s))
                    .toList();

            String json = objectMapper.writeValueAsString(requestBody);
            String url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=" + targetLang;

            Request request = new Request.Builder()
                    .url(url)
                    .header("Ocp-Apim-Subscription-Key", translatorKey)
                    .header("Ocp-Apim-Subscription-Region", translatorRegion)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "no body";
                    log.error("Azure Translator failed: {} - {}", response.code(), errorBody);
                    throw new ServiceUnavailableException("Translate", new RuntimeException("Azure Translator returned " + response.code()));
                }

                String responseBody = response.body().string();
                // Azure response: [{"translations":[{"text":"...","to":"zh-Hant"}]}, ...]
                List<Map<String, Object>> azureResponse = objectMapper.readValue(responseBody, new TypeReference<>() {});

                List<String> translations = new ArrayList<>();
                for (Map<String, Object> item : azureResponse) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> trans = (List<Map<String, String>>) item.get("translations");
                    if (trans != null && !trans.isEmpty()) {
                        translations.add(trans.get(0).get("text"));
                    }
                }
                return translations;
            }
        } catch (IOException e) {
            log.error("Azure Translator request failed", e);
            throw new ServiceUnavailableException("Translate", e);
        }
    }
}
