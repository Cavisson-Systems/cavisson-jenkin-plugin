package com.cavisson.jenkins.ai.testcase.exception;

public class CavAIApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    public CavAIApiException(String message) {
        super(message);
        this.httpStatus = -1;
    }

    public CavAIApiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public CavAIApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
    }

    public CavAIApiException(String message, int httpStatus, Throwable cause) {
        super(message, cause);   // ← FIXED: was super(message, httpStatus, cause) which doesn't exist
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String toString() {
        if (httpStatus > 0) {
            return "CavAIApiException [HTTP " + httpStatus + "]: " + getMessage();
        }
        return "CavAIApiException: " + getMessage();
    }
}
