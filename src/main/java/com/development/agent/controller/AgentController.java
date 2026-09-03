package com.development.agent.controller;

import com.development.agent.entity.User;
import com.development.agent.exception.ErrorResponse;
import com.development.agent.job.ChatJob;
import com.development.agent.model.ChatMessage;
import com.development.agent.model.ChatRequest;
import com.development.agent.model.ChatResponse;
import com.development.agent.service.AiAgentService;
import com.development.agent.service.ChatJobService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AiAgentService agentService;
    private final ChatJobService chatJobService;

    public AgentController(AiAgentService agentService, ChatJobService chatJobService) {
        this.agentService = agentService;
        this.chatJobService = chatJobService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal User user) {
        long start = System.currentTimeMillis();
        log.info("POST /api/agent/chat - conversationId={}, messageLength={}, user={}",
                request.getConversationId(),
                request.getMessage() == null ? 0 : request.getMessage().length(), user.getUsername());
        ChatResponse response = agentService.chat(request, user);
        log.info("POST /api/agent/chat - completed in {} ms, conversationId={}, replyLength={}, model={}",
                System.currentTimeMillis() - start, response.getConversationId(),
                response.getReply() == null ? 0 : response.getReply().length(), response.getModel());
        return response;
    }

    /**
     * Asynchronous chat: returns a jobId immediately and processes the turn in the
     * background. Client polls GET /api/agent/jobs/{jobId}/status. This path avoids
     * blocking an HTTP thread on upstream AI inference (scale-ready).
     */
    @PostMapping("/chat/async")
    public ResponseEntity<?> chatAsync(@Valid @RequestBody ChatRequest request,
                                       @AuthenticationPrincipal User user) {
        log.info("POST /api/agent/chat/async - conversationId={}, user={}",
                request.getConversationId(), user.getUsername());
        if (!chatJobService.isAsyncEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("ASYNC_DISABLED",
                            "Asynchronous processing is not enabled on this instance.",
                            System.currentTimeMillis()));
        }
        String jobId = chatJobService.submit(request, user);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "state", "PENDING",
                "message", "Job queued. Poll GET /api/agent/jobs/{jobId}/status"
        ));
    }

    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<?> jobStatus(@PathVariable String jobId,
                                       @AuthenticationPrincipal User user) {
        ChatJob job = chatJobService.get(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("JOB_NOT_FOUND", "No such job: " + jobId, System.currentTimeMillis()));
        }
        if (!job.getUserId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("ACCESS_DENIED", "Access denied to this job", System.currentTimeMillis()));
        }
        return ResponseEntity.ok(chatJobService.statusView(job));
    }

    /**
     * Server-Sent Events stream for a job. The client connects with its Bearer token
     * (via fetch, not EventSource, so the header can be sent) and receives a {@code status}
     * event immediately (current snapshot) and again when the job reaches a terminal state,
     * at which point the connection is closed. Use as the primary mechanism; fall back to
     * {@code /jobs/{jobId}/status} polling for clients that cannot or do not want to stream.
     */
    @GetMapping(value = "/jobs/{jobId}/stream", produces = "text/event-stream")
    public SseEmitter jobStream(@PathVariable String jobId,
                                @AuthenticationPrincipal User user,
                                HttpServletResponse response) {
        // SSE must never be cached or buffered: no-cache (an EventSource-style stream is
        // consumed incrementally) and X-Accel-Buffering: no (disable reverse-proxy buffering).
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return chatJobService.stream(jobId, user);
    }

    @GetMapping("/conversation/{conversationId}")
    public List<ChatMessage> getConversation(@PathVariable String conversationId,
                                             @AuthenticationPrincipal User user) {
        long start = System.currentTimeMillis();
        log.info("GET /api/agent/conversation/{} user={}", conversationId, user.getUsername());
        List<ChatMessage> messages = agentService.getConversation(conversationId, user);
        log.info("GET /api/agent/conversation/{} - returned {} messages in {} ms",
                conversationId, messages.size(), System.currentTimeMillis() - start);
        return messages;
    }

    @DeleteMapping("/conversation/{conversationId}")
    public Map<String, Boolean> clearConversation(@PathVariable String conversationId,
                                                    @AuthenticationPrincipal User user) {
        long start = System.currentTimeMillis();
        log.info("DELETE /api/agent/conversation/{} user={}", conversationId, user.getUsername());
        // Missing or non-owned conversations throw AiAgentException(404) -> unified ErrorResponse.
        agentService.clearConversation(conversationId, user);
        log.info("DELETE /api/agent/conversation/{} - cleared in {} ms",
                conversationId, System.currentTimeMillis() - start);
        return Map.of("cleared", true);
    }

    @DeleteMapping("/conversation/{conversationId}/message/{messageId}")
    public Map<String, Boolean> deleteMessage(@PathVariable String conversationId,
                                                @PathVariable Long messageId,
                                                @AuthenticationPrincipal User user) {
        long start = System.currentTimeMillis();
        log.info("DELETE /api/agent/conversation/{}/message/{} user={}", conversationId, messageId, user.getUsername());
        // Missing or non-owned conversations/messages throw AiAgentException(404).
        agentService.deleteMessage(conversationId, messageId, user);
        log.info("DELETE /api/agent/conversation/{}/message/{} - deleted in {} ms",
                conversationId, messageId, System.currentTimeMillis() - start);
        return Map.of("deleted", true);
    }

    @PostMapping("/conversation")
    public Map<String, String> newConversation(@AuthenticationPrincipal User user) {
        long start = System.currentTimeMillis();
        log.info("POST /api/agent/conversation - creating new conversation for user={}", user.getUsername());
        String conversationId = agentService.newConversation(user);
        log.info("POST /api/agent/conversation - created {} in {} ms",
                conversationId, System.currentTimeMillis() - start);
        return Map.of("conversationId", conversationId);
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getUserSessions(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(agentService.getUserSessions(user));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<?> getSuggestions(@AuthenticationPrincipal User user) {
        long start = System.currentTimeMillis();
        log.info("GET /api/agent/suggestions user={}", user.getUsername());
        List<String> suggestions = agentService.generateSuggestions(user);
        log.info("GET /api/agent/suggestions - returned {} suggestions in {} ms",
                suggestions.size(), System.currentTimeMillis() - start);
        return ResponseEntity.ok(suggestions);
    }
}
