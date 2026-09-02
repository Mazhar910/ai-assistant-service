package com.development.agent.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "token_usage", indexes = {
        @Index(name = "idx_token_usage_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_token_usage_model", columnList = "model_name")
})
public class TokenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "tokens_input")
    private long tokensInput;

    @Column(name = "tokens_output")
    private long tokensOutput;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TokenUsage() {}

    public TokenUsage(User user, long tokensInput, long tokensOutput, String modelName) {
        this.user = user;
        this.tokensInput = tokensInput;
        this.tokensOutput = tokensOutput;
        this.modelName = modelName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public long getTokensInput() { return tokensInput; }
    public void setTokensInput(long tokensInput) { this.tokensInput = tokensInput; }

    public long getTokensOutput() { return tokensOutput; }
    public void setTokensOutput(long tokensOutput) { this.tokensOutput = tokensOutput; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
