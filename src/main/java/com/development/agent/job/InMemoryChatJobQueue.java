package com.development.agent.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
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

    private volatile Consumer<ChatJob> processor;

    public InMemoryChatJobQueue(
            @Value("${app.async.core-pool-size:8}") int core,
            @Value("${app.async.max-pool-size:32}") int max,
            @Value("${app.async.queue-capacity:5000}") int capacity) {
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
        log.info("InMemoryChatJobQueue initialized (core={}, max={}, queue={})", core, max, capacity);
    }

    /** Register the function that executes a job. Called once at startup. */
    public void setProcessor(Consumer<ChatJob> processor) {
        this.processor = processor;
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
