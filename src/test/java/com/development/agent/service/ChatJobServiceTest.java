package com.development.agent.service;

import com.development.agent.entity.User;
import com.development.agent.exception.AiAgentException;
import com.development.agent.job.ChatJob;
import com.development.agent.job.ChatJobQueue;
import com.development.agent.job.JobStreamNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that opening a status stream enforces job ownership: an unknown job is
 * a 404, someone else's job is a 403, and the owner is handed a subscribed emitter.
 */
class ChatJobServiceTest {

    private ChatJobQueue queue;
    private AiAgentService aiAgentService;
    private JobStreamNotifier notifier;
    private ChatJobService service;

    @BeforeEach
    void setUp() {
        queue = mock(ChatJobQueue.class);
        aiAgentService = mock(AiAgentService.class);
        notifier = mock(JobStreamNotifier.class);
        service = new ChatJobService(queue, aiAgentService, notifier);
    }

    private User user(Long id) {
        User u = new User("user-" + id, "user" + id + "@example.com", "password");
        u.setId(id);
        return u;
    }

    @Test
    void streamUnknownJobThrows404() {
        when(queue.get("missing")).thenReturn(null);

        AiAgentException ex = assertThrows(AiAgentException.class,
                () -> service.stream("missing", user(1L)));

        assertEquals(404, ex.getStatus());
        assertEquals("JOB_NOT_FOUND", ex.getCode());
    }

    @Test
    void streamAnotherUsersJobThrows403() {
        ChatJob job = new ChatJob("job-9", 2L, "conv-9", "hello");
        when(queue.get("job-9")).thenReturn(job);

        AiAgentException ex = assertThrows(AiAgentException.class,
                () -> service.stream("job-9", user(1L)));

        assertEquals(403, ex.getStatus());
        assertEquals("ACCESS_DENIED", ex.getCode());
    }

    @Test
    void streamOwnerGetsSubscribedEmitter() {
        ChatJob job = new ChatJob("job-9", 1L, "conv-9", "hello");
        when(queue.get("job-9")).thenReturn(job);

        SseEmitter expected = new SseEmitter();
        when(notifier.subscribe(eq("job-9"), any(Map.class), anyBoolean())).thenReturn(expected);

        SseEmitter emitter = service.stream("job-9", user(1L));

        assertSame(expected, emitter);
        verify(notifier).subscribe(eq("job-9"), any(Map.class), anyBoolean());
    }

    @Test
    void streamOfAlreadyTerminalJobPassesTerminalFlag() {
        ChatJob job = new ChatJob("job-5", 1L, "conv-5", "hello");
        job.setState(ChatJob.State.COMPLETED);
        job.setResult("done");
        when(queue.get("job-5")).thenReturn(job);

        service.stream("job-5", user(1L));

        verify(notifier).subscribe(eq("job-5"), any(Map.class), eq(true));
    }
}
