package com.development.agent.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Streams AI job status to clients over Server-Sent Events and guarantees that
 * every open {@link SseEmitter} is eventually released exactly once, so the
 * in-memory registry never leaks and no data is disclosed to the wrong caller.
 *
 * Ownership of a job is enforced by the caller ({@code ChatJobService}) before a
 * subscriber is registered; this class only transports status and manages the
 * emitter lifecycle.
 *
 * Lifecycle / leak-prevention:
 * <ul>
 *   <li>Each registered emitter has a hard timeout after which Spring fires
 *       {@link SseEmitter#onTimeout} and we remove it.</li>
 *   <li>Client disconnect / send failure triggers {@code onCompletion}/{@code onError}
 *       and we remove it.</li>
 *   <li>A periodic daemon heartbeat keeps idle connections alive and drops any that
 *       can no longer be written to.</li>
 *   <li>When a job reaches a terminal state the worker calls {@link #publishTerminal},
 *       which removes the emitter and completes it.</li>
 * </ul>
 * Removal always uses {@code remove(jobId, emitter)} (atomic remove-if-equal) so a
 * reconnected subscriber's newer emitter is never dropped by a stale cleanup callback.
 */
@Component
public class JobStreamNotifier {

    private static final Logger log = LoggerFactory.getLogger(JobStreamNotifier.class);

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat;
    private final long timeoutMs;
    private final long heartbeatMs;

    public JobStreamNotifier(
            @Value("${app.async.sse-timeout-seconds:900}") long timeoutSeconds,
            @Value("${app.async.sse-heartbeat-seconds:15}") long heartbeatSeconds) {
        this.timeoutMs = Math.max(1, timeoutSeconds) * 1000L;
        this.heartbeatMs = Math.max(1000, heartbeatSeconds) * 1000L;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long sweepMs = Math.max(5000, heartbeatMs);
        this.heartbeat.scheduleWithFixedDelay(this::heartbeatAll, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers a subscriber and returns an emitter the caller returns from the
     * controller. The {@code snapshot} (current job status) is sent immediately;
     * if the job is already terminal the emitter is completed right away because
     * there will be no further {@link #publishTerminal} from the worker.
     */
    public SseEmitter subscribe(String jobId, Map<String, Object> snapshot, boolean terminal) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onCompletion(() -> emitters.remove(jobId, emitter));
        emitter.onTimeout(() -> {
            emitters.remove(jobId, emitter);
            emitter.completeWithError(new IllegalStateException("SSE stream timed out for job " + jobId));
        });
        emitter.onError(t -> {
            emitters.remove(jobId, emitter);
            log.debug("SSE stream error for job {}: {}", jobId, t.getMessage());
        });

        SseEmitter previous = emitters.put(jobId, emitter);
        if (previous != null) {
            // A reconnect replaced this job's stream; close the stale one.
            previous.complete();
        }

        try {
            emitter.send(event(jobId, snapshot));
            if (terminal) {
                emitters.remove(jobId, emitter);
                emitter.complete();
            }
        } catch (Exception e) {
            emitters.remove(jobId, emitter);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // already completed/removed
            }
        }
        return emitter;
    }

    /**
     * Called by the job worker when a job reaches a terminal state. Sends the final
     * status and closes the stream. Removal happens before sending so a concurrent
     * subscribe that sees a terminal job already delivers the result itself.
     */
    public void publishTerminal(String jobId, Map<String, Object> terminalStatus) {
        SseEmitter emitter = emitters.remove(jobId);
        if (emitter == null) {
            return; // no active subscriber; a late subscribe reads the terminal job directly
        }
        try {
            emitter.send(event(jobId, terminalStatus));
            emitter.complete();
        } catch (Exception e) {
            log.debug("Could not deliver terminal SSE event for job {}: {}", jobId, e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // already closed
            }
        }
    }

    /** Sends a heartbeat comment to every open stream, dropping any that have died. */
    private void heartbeatAll() {
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                emitters.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private SseEmitter.SseEventBuilder event(String jobId, Object data) {
        return SseEmitter.event().name("status").data(data);
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
        for (SseEmitter emitter : emitters.values()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already closed
            }
        }
        emitters.clear();
        log.info("JobStreamNotifier shut down");
    }
}
