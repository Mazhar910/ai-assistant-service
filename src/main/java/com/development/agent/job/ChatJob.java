package com.development.agent.job;

import java.time.LocalDateTime;

/**
 * A unit of AI processing work (a single user chat turn) that is executed
 * asynchronously so HTTP threads are not blocked waiting on the upstream
 * OpenCode server.
 */
public class ChatJob {

    public enum State {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    private final String jobId;
    private final Long userId;
    private final String conversationId;
    private final String message;

    private volatile State state = State.PENDING;
    private volatile String result;
    private volatile String errorCode;
    private volatile String errorMessage;
    private final LocalDateTime submittedAt = LocalDateTime.now();

    public ChatJob(String jobId, Long userId, String conversationId, String message) {
        this.jobId = jobId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.message = message;
    }

    public String getJobId() { return jobId; }
    public Long getUserId() { return userId; }
    public String getConversationId() { return conversationId; }
    public String getMessage() { return message; }
    public State getState() { return state; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public String getResult() { return result; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }

    public void setState(State state) { this.state = state; }
    public void setResult(String result) { this.result = result; }
    public void setError(String code, String message) {
        this.errorCode = code;
        this.errorMessage = message;
    }
}
