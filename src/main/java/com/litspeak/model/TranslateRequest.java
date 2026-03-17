package com.litspeak.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class TranslateRequest {
    @NotEmpty(message = "sentences is required")
    @Size(max = 10, message = "sentences must not exceed 10")
    private List<@NotBlank @Size(max = 1000) String> sentences;

    @NotBlank(message = "target_lang is required")
    private String targetLang;

    public List<String> getSentences() { return sentences; }
    public void setSentences(List<String> sentences) { this.sentences = sentences; }
    public String getTargetLang() { return targetLang; }
    public void setTargetLang(String targetLang) { this.targetLang = targetLang; }
}
