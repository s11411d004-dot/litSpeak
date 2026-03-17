package com.litspeak.exception;

public class QuotaExceededException extends RuntimeException {
    private final String service;
    public QuotaExceededException(String service) {
        super("Monthly " + service + " quota exceeded");
        this.service = service;
    }
    public String getService() { return service; }
}
