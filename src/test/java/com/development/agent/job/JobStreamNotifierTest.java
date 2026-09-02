package com.development.agent.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link JobStreamNotifier} releases every registered emitter and
 * never leaks its internal registry, for both the terminal-at-subscribe path and
 * the worker-publishes-terminal path.
 */
class JobStreamNotifierTest {

    private final JobStreamNotifier notifier = new JobStreamNotifier(900, 15);

    @AfterEach
    void tearDown() {
        notifier.shutdown();
    }

    @SuppressWarnings("unchecked")
    private Map<String, SseEmitter> emitters() {
        return (Map<String, SseEmitter>) ReflectionTestUtils.getField(notifier, "emitters");
    }

    @Test
    void subscribeToAlreadyTerminalJobCompletesAndLeavesNothingRegistered() {
        Map<String, Object> snapshot = Map.of("state", "COMPLETED", "reply", "hi");
        SseEmitter emitter = notifier.subscribe("job-1", snapshot, true);

        assertNotNull(emitter);
        // A terminal subscribe must not leave a dangling emitter (no leak).
        assertFalse(emitters().containsKey("job-1"));
    }

    @Test
    void nonTerminalSubscribeStaysRegisteredUntilPublishTerminal() {
        Map<String, Object> running = Map.of("state", "RUNNING");
        SseEmitter emitter = notifier.subscribe("job-2", running, false);

        assertTrue(emitters().containsKey("job-2"));

        // Simulate the worker finishing the job.
        notifier.publishTerminal("job-2", Map.of("state", "COMPLETED", "reply", "world"));

        assertFalse(emitters().containsKey("job-2"));
    }

    @Test
    void publishTerminalWithoutSubscriberIsANoOp() {
        // Should not throw when no one is subscribed.
        notifier.publishTerminal("job-3", Map.of("state", "FAILED"));
        assertFalse(emitters().containsKey("job-3"));
    }

    @Test
    void reconnectingSubscriberReplacesStaleEmitterWithoutLeak() {
        notifier.subscribe("job-4", Map.of("state", "RUNNING"), false);
        assertTrue(emitters().containsKey("job-4"));

        // A reconnect adds a second emitter for the same job; the stale one is completed.
        SseEmitter second = notifier.subscribe("job-4", Map.of("state", "RUNNING"), false);
        assertNotNull(second);

        // Exactly one emitter remains registered for the job (not two).
        assertEquals(1, emitters().size());

        notifier.publishTerminal("job-4", Map.of("state", "COMPLETED"));
        // The stream is finished and the registration is gone (no leak).
        assertEquals(0, emitters().size());
    }
}
