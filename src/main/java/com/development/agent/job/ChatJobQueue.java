package com.development.agent.job;

/**
 * Abstraction over the AI job queue so the in-memory implementation
 * (suitable for a single node / local dev) can later be swapped for a
 * distributed queue (Redis, RabbitMQ, SQS) without touching callers.
 */
public interface ChatJobQueue {

    /** Submit a job for processing. Must be non-blocking. */
    void submit(ChatJob job);

    /** Retrieve a job by id (null if not found). */
    ChatJob get(String jobId);

    /** Current number of queued-but-not-yet-started jobs. */
    int pendingCount();
}
