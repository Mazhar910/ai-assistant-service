package com.development.agent.entity;

/**
 * Application roles. Persisted by {@link User} via {@code @Enumerated(EnumType.STRING)}
 * so database values remain the plain names ({@code USER} / {@code ADMIN}).
 */
public enum Role {
    USER,
    ADMIN
}