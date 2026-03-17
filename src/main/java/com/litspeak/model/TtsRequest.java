package com.litspeak.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TtsRequest {
    @NotBlank(message = "text is required")
    @Size(max = 5000, message = "text must not exceed 5000 characters")
    private String text;

    @NotBlank(message = "voice_code is required")
    private String voiceCode;

    private int speed = 0; // -40, -20, 0, 20

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getVoiceCode() { return voiceCode; }
    public void setVoiceCode(String voiceCode) { this.voiceCode = voiceCode; }
    public int getSpeed() { return speed; }
    public void setSpeed(int speed) { this.speed = speed; }
}
