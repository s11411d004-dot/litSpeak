package com.litspeak.exception;

public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String service, Throwable cause) {
        super(service + " service unavailable", cause);
    }
}
