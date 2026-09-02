package com.development.agent.service;

import com.development.agent.entity.User;
import com.development.agent.exception.AiAgentException;
import com.development.agent.job.ChatJob;
import com.development.agent.job.ChatJobQueue;
import com.development.agent.job.InMemoryChatJobQueue;
import com.development.agent.model.ChatRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates asynchronous AI job execution. The web layer submits a job and
 * returns immediately; a worker processes it in the background. The queue is
 * abstracted behind {@link ChatJobQueue} (in-memory today, distributed later),
 * so this service does not need to change when scaling out.
 *
 * When {@code ai.opencode.async=true} the web layer uses this path instead of
 * the blocking synchronous call, avoiding tying up request threads during AI
 * inference.
 */
@Service
public class ChatJobService {

    private static final Logger log = LoggerFactory.getLogger(ChatJobService.class);

    private final ChatJobQueue jobQueue;
    private final AiAgentService aiAgentService;

    @Value("${ai.opencode.async:false}")
    private boolean asyncEnabled;

    @Value("${app.async.retry-max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${app.async.retry-base-delay-ms:2000}")
    private long retryBaseDelayMs;

    public ChatJobService(ChatJobQueue jobQueue, AiAgentService aiAgentService) {
        this.jobQueue = jobQueue;
        this.aiAgentService = aiAgentService;
    }

    @PostConstruct
    public void init() {
        if (jobQueue instanceof InMemoryChatJobQueue inMemory) {
            inMemory.setProcessor(this::process);
        }
        log.info("ChatJobService initialized (async={})", asyncEnabled);
    }

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    /** Submit a job and return its id immediately (non-blocking). */
    public String submit(ChatRequest request, User user) {
        // Resolve the conversation here (create or verify ownership) so every retry
        // attempt of the job reuses the SAME conversation instead of creating orphans.
        String conversationId = aiAgentService.resolveConversationForJob(
                user, request.getConversationId(), request.getMessage());
        ChatJob job = new ChatJob(UUID.randomUUID().toString(), user.getId(),
                conversationId, request.getMessage());
        jobQueue.submit(job);
        log.info("AI job {} submitted for user {} (queue depth={})",
                job.getJobId(), user.getUsername(), jobQueue.pendingCount());
        return job.getJobId();
    }

    public ChatJob get(String jobId) {
        return jobQueue.get(jobId);
    }

    /** Runs on a worker thread, with retries for transient rate-limit (429) failures. */
    private void process(ChatJob job) {
        job.setState(ChatJob.State.RUNNING);

        try {
            String reply = executeWithRetry(job);
            job.setResult(reply);
            job.setState(ChatJob.State.COMPLETED);
            log.info("AI job {} completed", job.getJobId());
        } catch (AiAgentException e) {
            job.setState(ChatJob.State.FAILED);
            job.setError("PROCESSING_FAILED", e.getMessage());
            log.error("AI job {} failed: {}", job.getJobId(), e.getMessage());
        } catch (Exception e) {
            job.setState(ChatJob.State.FAILED);
            job.setError("PROCESSING_FAILED", e.getMessage());
            log.error("AI job {} failed: {}", job.getJobId(), e.getMessage());
        }
    }

    /**
     * Executes the chat turn, retrying with exponential backoff while the upstream
     * is rate-limiting (HTTP 429). All other failures propagate immediately.
     */
    private String executeWithRetry(ChatJob job) {
        int attempt = 1;
        while (true) {
            try {
                return aiAgentService.executeChatJob(
                        job.getUserId(), job.getConversationId(), job.getMessage());
            } catch (AiAgentException e) {
                if (e.getStatus() == 429 && attempt < Math.max(1, retryMaxAttempts)) {
                    long delay = retryBaseDelayMs * (long) Math.min(8, Math.pow(2, attempt - 1));
                    log.warn("AI job {} rate-limited on attempt {} of {}, retrying in {} ms",
                            job.getJobId(), attempt, retryMaxAttempts, delay);
                    sleep(delay);
                    attempt++;
                    continue;
                }
                throw e;
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to retry job", e);
        }
    }

    /** Serializable status view for the API (never exposes internal internals). */
    public Map<String, Object> statusView(ChatJob job, User user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job.getJobId());
        view.put("conversationId", job.getConversationId());
        view.put("state", job.getState().name());
        view.put("submittedAt", job.getSubmittedAt());
        if (job.getState() == ChatJob.State.COMPLETED) {
            view.put("reply", job.getResult());
        } else if (job.getState() == ChatJob.State.FAILED) {
            view.put("errorCode", job.getErrorCode());
            view.put("error", job.getErrorMessage());
        }
        return view;
    }
}
