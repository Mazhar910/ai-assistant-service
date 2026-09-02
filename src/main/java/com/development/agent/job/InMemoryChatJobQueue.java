package com.development.agent.job;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory AI job queue. Suitable for a single node / local dev. To scale to
 * multiple app nodes, swap this for a distributed implementation (Redis,
 * RabbitMQ, SQS) behind the {@link ChatJobQueue} interface.
 *
 * Worker threads execute a registered {@link Consumer} against each job. The
 * consumer (which runs the actual OpenCode call + persistence) is provided by
 * the orchestrating service to avoid circular bean wiring.
 */
@Component
public class InMemoryChatJobQueue implements ChatJobQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryChatJobQueue.class);

    private final ConcurrentHashMap<String, ChatJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final BlockingQueue<Runnable> queue;
    private final ScheduledExecutorService janitor;

    private volatile Consumer<ChatJob> processor;

    public InMemoryChatJobQueue(
            @Value("${app.async.core-pool-size:8}") int core,
            @Value("${app.async.max-pool-size:32}") int max,
            @Value("${app.async.queue-capacity:5000}") int capacity,
            @Value("${app.async.job-retention-hours:24}") long retentionHours) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        AtomicInteger threadCounter = new AtomicInteger(0);
        this.workers = new ThreadPoolExecutor(
                core, max, 60, TimeUnit.SECONDS, queue,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("ai-job-worker-" + threadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        // Periodic eviction of COMPLETED/FAILED jobs so the jobs map doesn't grow unboundedly.
        long retentionMs = Math.max(1, retentionHours) * 3_600_000L;
        long sweepMs = Math.max(60_000L, retentionMs / 2);
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-job-janitor");
            t.setDaemon(true);
            return t;
        });
        this.janitor.scheduleWithFixedDelay(() -> evictFinishedJobs(retentionMs), sweepMs, sweepMs, TimeUnit.MILLISECONDS);

        log.info("InMemoryChatJobQueue initialized (core={}, max={}, queue={}, retention={}h)",
                core, max, capacity, Math.max(1, retentionHours));
    }

    private void evictFinishedJobs(long retentionMs) {
        long cutoff = System.currentTimeMillis() - retentionMs;
        int evicted = 0;
        for (ChatJob job : jobs.values()) {
            ChatJob.State state = job.getState();
            if ((state == ChatJob.State.COMPLETED || state == ChatJob.State.FAILED) &&
                    job.getSubmittedAt() != null &&
                    job.getSubmittedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() < cutoff) {
                jobs.remove(job.getJobId(), job);
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("Evicted {} finished AI jobs older than retention ({} ms)", evicted, retentionMs);
        }
    }

    /** Register the function that executes a job. Called once at startup. */
    public void setProcessor(Consumer<ChatJob> processor) {
        this.processor = processor;
    }

    /** Gracefully stop the worker pool and janitor when the application shuts down. */
    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        janitor.shutdownNow();
        log.info("InMemoryChatJobQueue shut down ({} in-flight jobs)", jobs.size());
    }

    @Override
    public void submit(ChatJob job) {
        jobs.put(job.getJobId(), job);
        try {
            workers.execute(() -> run(job));
        } catch (Exception e) {
            // Queue full / rejected -> mark failed so the client can retry
            job.setState(ChatJob.State.FAILED);
            job.setError("QUEUE_FULL", "The AI processing queue is currently full. Please retry.");
            log.warn("AI job rejected (queue full) for job {}", job.getJobId());
        }
    }

    private void run(ChatJob job) {
        Consumer<ChatJob> p = this.processor;
        if (p == null) {
            job.setState(ChatJob.State.FAILED);
            job.setError("NO_PROCESSOR", "Job processor not registered");
            return;
        }
        try {
            p.accept(job);
        } catch (Throwable t) {
            job.setState(ChatJob.State.FAILED);
            job.setError("INTERNAL", t.getMessage());
            log.error("Unhandled error processing AI job {}", job.getJobId(), t);
        }
    }

    @Override
    public ChatJob get(String jobId) {
        return jobs.get(jobId);
    }

    @Override
    public int pendingCount() {
        return queue.size();
    }
}
