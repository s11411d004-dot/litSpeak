package com.litspeak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litspeak.exception.ServiceUnavailableException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
public class AssessmentService {
    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String speechKey;
    private final String region;

    public AssessmentService(
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${azure.speech.key}") String speechKey,
            @Value("${azure.region}") String region) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.speechKey = speechKey;
        this.region = region;
    }

    public Map<String, Object> assess(byte[] audioData, String referenceText) {
        try {
            // Strip surrounding quotes if present (e.g. "Do not..." → Do not...)
            referenceText = referenceText.strip();
            if (referenceText.length() >= 2
                    && referenceText.startsWith("\"") && referenceText.endsWith("\"")) {
                referenceText = referenceText.substring(1, referenceText.length() - 1).strip();
            }

            log.info("Assessment request: referenceText='{}', audioSize={} bytes", referenceText, audioData.length);

            // Log first few bytes to verify audio format (WAV should start with "RIFF")
            if (audioData.length >= 4) {
                String header = new String(audioData, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                log.info("Audio header bytes: [{}] (expected 'RIFF' for WAV format)", header);
                if (!"RIFF".equals(header)) {
                    log.warn("Audio does NOT appear to be WAV format! Header: [{}]. This may cause Azure to return 0 scores.", header);
                }
            }

            // Build pronunciation assessment config
            String assessmentConfig = Base64.getEncoder().encodeToString(
                    objectMapper.writeValueAsBytes(Map.of(
                            "ReferenceText", referenceText,
                            "GradingSystem", "HundredMark",
                            "Granularity", "Phoneme",
                            "Dimension", "Comprehensive"
                    ))
            );

            log.debug("Assessment config (decoded): ReferenceText='{}', GradingSystem=HundredMark, Granularity=Phoneme, Dimension=Comprehensive", referenceText);

            String url = "https://" + region + ".stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
                    + "?language=en-US&format=detailed";

            Request request = new Request.Builder()
                    .url(url)
                    .header("Ocp-Apim-Subscription-Key", speechKey)
                    .header("Pronunciation-Assessment", assessmentConfig)
                    .header("Content-Type", "audio/wav")
                    .header("Accept", "application/json")
                    .post(RequestBody.create(audioData, MediaType.parse("audio/wav")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "no body";
                    log.error("Azure Assessment failed: {} - {}", response.code(), errorBody);
                    throw new ServiceUnavailableException("Assessment", new RuntimeException("Azure returned " + response.code()));
                }

                String responseBody = response.body().string();
                log.info("Azure Assessment raw response: {}", responseBody);
                JsonNode root = objectMapper.readTree(responseBody);
                return parseAssessmentResult(root);
            }
        } catch (IOException e) {
            log.error("Azure Assessment request failed", e);
            throw new ServiceUnavailableException("Assessment", e);
        }
    }

    private Map<String, Object> parseAssessmentResult(JsonNode root) {
        Map<String, Object> result = new HashMap<>();

        // Log RecognitionStatus to check if Azure recognized the audio
        String recognitionStatus = root.path("RecognitionStatus").asText("N/A");
        log.info("Azure RecognitionStatus: {}", recognitionStatus);
        if (!"Success".equals(recognitionStatus)) {
            log.warn("RecognitionStatus is '{}', not 'Success'. Audio may not have been recognized properly.", recognitionStatus);
        }

        JsonNode nBest = root.path("NBest");
        if (!nBest.isArray() || nBest.isEmpty()) {
            log.warn("NBest array is missing or empty in Azure response. Returning empty scores.");
        }
        if (nBest.isArray() && nBest.size() > 0) {
            JsonNode best = nBest.get(0);
            JsonNode pronAssessment = best.path("PronunciationAssessment");

            double overallScore = pronAssessment.path("PronScore").asDouble(0);
            result.put("overall_score", Math.round(overallScore));

            // accuracy, fluency, completeness scores
            result.put("accuracy_score", Math.round(pronAssessment.path("AccuracyScore").asDouble(0)));
            result.put("fluency_score", Math.round(pronAssessment.path("FluencyScore").asDouble(0)));
            result.put("completeness_score", Math.round(pronAssessment.path("CompletenessScore").asDouble(0)));

            List<Map<String, Object>> wordScores = new ArrayList<>();
            JsonNode words = best.path("Words");
            if (words.isArray()) {
                for (JsonNode word : words) {
                    Map<String, Object> wordScore = new HashMap<>();
                    wordScore.put("word", word.path("Word").asText());
                    JsonNode wordAssessment = word.path("PronunciationAssessment");
                    wordScore.put("score", Math.round(wordAssessment.path("AccuracyScore").asDouble(0)));

                    List<Map<String, Object>> phonemes = new ArrayList<>();
                    JsonNode phonemeNodes = word.path("Phonemes");
                    if (phonemeNodes.isArray()) {
                        for (JsonNode ph : phonemeNodes) {
                            Map<String, Object> phoneme = new HashMap<>();
                            phoneme.put("phoneme", ph.path("Phoneme").asText());
                            phoneme.put("score", Math.round(ph.path("PronunciationAssessment").path("AccuracyScore").asDouble(0)));
                            phonemes.add(phoneme);
                        }
                    }
                    wordScore.put("phonemes", phonemes);
                    wordScores.add(wordScore);
                }
            }
            result.put("word_scores", wordScores);
        }

        return result;
    }
}
