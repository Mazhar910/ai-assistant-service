package com.development.agent.exception;

public class AiAgentException extends RuntimeException {

    private final String code;
    private final int status;

    public AiAgentException(String message, String code) {
        this(message, code, 0);
    }

    public AiAgentException(String message, String code, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    /** HTTP status returned by the upstream server, or 0 if not applicable. */
    public int getStatus() {
        return status;
    }
}