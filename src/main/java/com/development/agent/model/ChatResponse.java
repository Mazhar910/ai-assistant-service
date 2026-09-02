package com.development.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    private String conversationId;
    private String reply;
    private LocalDateTime timestamp;
    private String model;

    public ChatResponse() {
    }

    public ChatResponse(String conversationId, String reply, LocalDateTime timestamp) {
        this(conversationId, reply, timestamp, null);
    }

    public ChatResponse(String conversationId, String reply, LocalDateTime timestamp, String model) {
        this.conversationId = conversationId;
        this.reply = reply;
        this.timestamp = timestamp;
        this.model = model;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    /** Model that produced this reply, when one was selected via the fallback chain. */
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
