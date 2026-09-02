package com.development.agent.service;

import com.development.agent.client.OpencodeClient;
import com.development.agent.entity.ChatMessageEntity;
import com.development.agent.entity.Conversation;
import com.development.agent.entity.TokenUsage;
import com.development.agent.entity.User;
import com.development.agent.exception.AiAgentException;
import com.development.agent.model.ChatMessage;
import com.development.agent.model.ChatRequest;
import com.development.agent.model.ChatResponse;
import com.development.agent.repository.ChatMessageRepository;
import com.development.agent.repository.ConversationRepository;
import com.development.agent.repository.TokenUsageRepository;
import com.development.agent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAgentServiceTest {

    @Mock
    private OpencodeClient opencodeClient;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private TokenUsageRepository tokenUsageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AiAgentService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AiAgentService(opencodeClient, conversationRepository, chatMessageRepository,
                tokenUsageRepository, userRepository, transactionManager);
        user = new User("alice", "alice@example.com", "pw");
        user.setId(1L);
        lenient().when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tokenUsageRepository.save(any(TokenUsage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ChatRequest req(String conversationId, String message) {
        ChatRequest r = new ChatRequest();
        r.setConversationId(conversationId);
        r.setMessage(message);
        return r;
    }

    private Conversation conversation(String externalId, String opencodeSessionId) {
        Conversation c = new Conversation(externalId, user, "Chat");
        c.setId(1L);
        c.setOpencodeSessionId(opencodeSessionId);
        return c;
    }

    @Test
    void followUpsReuseTheSameOpencodeSessionAcrossTurns() {
        // A persisted conversation bound to an OpenCode session.
        Conversation convo = conversation("conv-1", "oc-1");
        when(conversationRepository.findByExternalId("conv-1")).thenReturn(Optional.of(convo));
        when(opencodeClient.sendMessage(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "reply to: " + inv.getArgument(2));

        service.chat(req("conv-1", "Give me a study routine"), user);
        service.chat(req("conv-1", "yes"), user);

        verify(opencodeClient, times(2))
                .sendMessage(eq("oc-1"), anyString(), anyString());
    }

    @Test
    void contextualFollowUpsAreSentToTheSameSessionWithoutCreatingNewOnes() {
        Conversation convo = conversation("conv-2", "oc-2");
        when(conversationRepository.findByExternalId("conv-2")).thenReturn(Optional.of(convo));
        when(opencodeClient.sendMessage(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "reply to: " + inv.getArgument(2));

        service.chat(req("conv-2", "Give me a study routine"), user);
        service.chat(req("conv-2", "yes"), user);
        service.chat(req("conv-2", "no"), user);
        service.chat(req("conv-2", "do it"), user);
        service.chat(req("conv-2", "continue"), user);
        service.chat(req("conv-2", "the second one"), user);
        service.chat(req("conv-2", "make it shorter"), user);

        verify(opencodeClient, times(7)).sendMessage(eq("oc-2"), anyString(), anyString());
        verify(opencodeClient, times(0)).createSession();
    }

    @Test
    void separateConversationsHaveIsolatedOpenCodeSessions() {
        Conversation convoA = conversation("conv-a", "oc-a");
        Conversation convoB = conversation("conv-b", "oc-b");
        when(conversationRepository.findByExternalId("conv-a")).thenReturn(Optional.of(convoA));
        when(conversationRepository.findByExternalId("conv-b")).thenReturn(Optional.of(convoB));
        when(opencodeClient.sendMessage(anyString(), anyString(), anyString()))
                .thenReturn("ok");

        service.chat(req("conv-a", "hello"), user);
        service.chat(req("conv-b", "hello"), user);

        verify(opencodeClient).sendMessage(eq("oc-a"), anyString(), anyString());
        verify(opencodeClient).sendMessage(eq("oc-b"), anyString(), anyString());
    }

    @Test
    void eachTurnPersistsAUserAndAssistantMessage() {
        Conversation convo = conversation("conv-3", "oc-3");
        when(conversationRepository.findByExternalId("conv-3")).thenReturn(Optional.of(convo));
        when(opencodeClient.sendMessage(anyString(), anyString(), anyString()))
                .thenReturn("assistant reply");

        service.chat(req("conv-3", "hello"), user);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageRepository, times(2)).save(captor.capture());
        List<ChatMessageEntity> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("hello", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("assistant reply", saved.get(1).getContent());
    }

    @Test
    void sessionFailureRecoversByReplayingPersistedHistoryIntoFreshSession() {
        Conversation convo = conversation("conv-4", "oc-old");
        convo.setId(7L);
        when(conversationRepository.findByExternalId("conv-4")).thenReturn(Optional.of(convo));

        // Prior persisted history: turn 1 user+assistant (used to re-establish context).
        ChatMessageEntity priorUser = new ChatMessageEntity(convo, "user", "Give me a study routine");
        ChatMessageEntity priorAssistant = new ChatMessageEntity(convo, "assistant", "Here is a routine... yes?");
        when(chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(7L))
                .thenReturn(List.of(priorUser, priorAssistant));

        // First attempt on the old, unusable session fails; fresh session works.
        when(opencodeClient.createSession()).thenReturn("oc-new");
        when(opencodeClient.sendMessage(eq("oc-old"), anyString(), anyString()))
                .thenThrow(new AiAgentException("Failed to reach opencode server: timeout", "UPSTREAM_ERROR"));
        when(opencodeClient.sendMessage(eq("oc-new"), anyString(), anyString()))
                .thenReturn("I will customize your study plan. Which subject?");

        ChatResponse response = service.chat(req("conv-4", "yes"), user);

        // History was replayed into the fresh session before resending "yes".
        ArgumentCaptor<List<OpencodeClient.MapMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(opencodeClient).replayHistory(eq("oc-new"), anyString(), historyCaptor.capture());
        List<OpencodeClient.MapMessage> replayed = historyCaptor.getValue();
        assertEquals(2, replayed.size());
        assertEquals("user", replayed.get(0).role());
        assertEquals("Give me a study routine", replayed.get(0).content());
        assertEquals("assistant", replayed.get(1).role());
        assertEquals("Here is a routine... yes?", replayed.get(1).content());

        // "yes" was resent on the recovered session.
        verify(opencodeClient).sendMessage(eq("oc-new"), anyString(), eq("yes"));

        // Mapping updated to the fresh session and the app reply is returned.
        assertEquals("oc-new", convo.getOpencodeSessionId());
        assertEquals("I will customize your study plan. Which subject?", response.getReply());
    }

    @Test
    void recoveredConversationStillPersistsTheTurnAfterRecovery() {
        Conversation convo = conversation("conv-5", "oc-old");
        convo.setId(8L);
        when(conversationRepository.findByExternalId("conv-5")).thenReturn(Optional.of(convo));
        when(chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(8L)).thenReturn(List.of());
        when(opencodeClient.createSession()).thenReturn("oc-new");
        when(opencodeClient.sendMessage(anyString(), anyString(), anyString()))
                .thenThrow(new AiAgentException("Failed to reach opencode server: timeout", "UPSTREAM_ERROR"))
                .thenReturn("Understood!");
        lenient().when(opencodeClient.deleteSession(anyString())).thenReturn(true);

        ChatResponse response = service.chat(req("conv-5", "continue"), user);

        assertEquals("Understood!", response.getReply());
        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageRepository, times(2)).save(captor.capture());
        List<ChatMessageEntity> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("continue", saved.get(0).getContent());
        assertEquals("assistant", saved.get(1).getRole());
    }

    @Test
    void primaryModelCompletesTurnWhenNoRateLimitOccurs() {
        Conversation convo = conversation("conv-chain-ok", "oc-chain-ok");
        when(conversationRepository.findByExternalId("conv-chain-ok")).thenReturn(Optional.of(convo));
        ReflectionTestUtils.setField(service, "modelChainConfig",
                "opencode/big-pickle,opencode/ling-3.0-flash-fin-free");
        when(opencodeClient.sendMessage(eq("oc-chain-ok"), anyString(), anyString(), eq("opencode/big-pickle")))
                .thenReturn("primary reply");

        ChatResponse response = service.chat(req("conv-chain-ok", "hello"), user);

        assertEquals("primary reply", response.getReply());
        verify(opencodeClient).sendMessage(eq("oc-chain-ok"), anyString(), anyString(), eq("opencode/big-pickle"));
        verify(opencodeClient, never())
                .sendMessage(eq("oc-chain-ok"), anyString(), anyString(), eq("opencode/ling-3.0-flash-fin-free"));
    }

    @Test
    void rateLimitedPrimaryModelFallsBackToNextModelInChain() {
        Conversation convo = conversation("conv-rl", "oc-rl");
        when(conversationRepository.findByExternalId("conv-rl")).thenReturn(Optional.of(convo));
        ReflectionTestUtils.setField(service, "modelChainConfig",
                "opencode/big-pickle,opencode/ling-3.0-flash-fin-free");
        when(opencodeClient.sendMessage(eq("oc-rl"), anyString(), anyString(), eq("opencode/big-pickle")))
                .thenThrow(new AiAgentException("rate limited", "RATE_LIMITED", 429));
        when(opencodeClient.sendMessage(eq("oc-rl"), anyString(), anyString(), eq("opencode/ling-3.0-flash-fin-free")))
                .thenReturn("fallback reply");

        ChatResponse response = service.chat(req("conv-rl", "hello"), user);

        assertEquals("fallback reply", response.getReply());
        verify(opencodeClient).sendMessage(eq("oc-rl"), anyString(), anyString(), eq("opencode/big-pickle"));
        verify(opencodeClient).sendMessage(eq("oc-rl"), anyString(), anyString(), eq("opencode/ling-3.0-flash-fin-free"));
    }

    @Test
    void nonRateLimitErrorDoesNotFallBackToNextModel() {
        Conversation convo = conversation("conv-nonrl", "oc-nonrl");
        when(conversationRepository.findByExternalId("conv-nonrl")).thenReturn(Optional.of(convo));
        ReflectionTestUtils.setField(service, "modelChainConfig",
                "opencode/big-pickle,opencode/ling-3.0-flash-fin-free");
        when(opencodeClient.sendMessage(eq("oc-nonrl"), anyString(), anyString(), eq("opencode/big-pickle")))
                .thenThrow(new AiAgentException("bad gateway", "UPSTREAM_ERROR", 502));

        assertThrows(AiAgentException.class, () -> service.chat(req("conv-nonrl", "hello"), user));
        verify(opencodeClient, never())
                .sendMessage(eq("oc-nonrl"), anyString(), anyString(), eq("opencode/ling-3.0-flash-fin-free"));
    }

    @Test
    void allModelsRateLimitedThrows429() {
        Conversation convo = conversation("conv-allrl", "oc-allrl");
        when(conversationRepository.findByExternalId("conv-allrl")).thenReturn(Optional.of(convo));
        ReflectionTestUtils.setField(service, "modelChainConfig",
                "opencode/big-pickle,opencode/ling-3.0-flash-fin-free");
        when(opencodeClient.sendMessage(eq("oc-allrl"), anyString(), anyString(), anyString()))
                .thenThrow(new AiAgentException("rate limited", "RATE_LIMITED", 429));

        AiAgentException ex = assertThrows(AiAgentException.class,
                () -> service.chat(req("conv-allrl", "hello"), user));

        assertEquals(429, ex.getStatus());
        assertEquals("RATE_LIMITED", ex.getCode());
    }

    @Test
    void rateLimitedPrimaryIsSkippedOnSubsequentTurnsDuringCooldown() {
        Conversation convo = conversation("conv-sticky", "oc-sticky");
        when(conversationRepository.findByExternalId("conv-sticky")).thenReturn(Optional.of(convo));
        ReflectionTestUtils.setField(service, "modelChainConfig",
                "opencode/big-pickle,opencode/ling-3.0-flash-fin-free");
        ReflectionTestUtils.setField(service, "rateLimitCooldownSeconds", 300L);
        when(opencodeClient.sendMessage(eq("oc-sticky"), anyString(), anyString(), eq("opencode/big-pickle")))
                .thenThrow(new AiAgentException("rate limited", "RATE_LIMITED", 429));
        when(opencodeClient.sendMessage(eq("oc-sticky"), anyString(), anyString(), eq("opencode/ling-3.0-flash-fin-free")))
                .thenReturn("fallback 1", "fallback 2");

        service.chat(req("conv-sticky", "first"), user);
        service.chat(req("conv-sticky", "second"), user);

        verify(opencodeClient, times(1))
                .sendMessage(eq("oc-sticky"), anyString(), anyString(), eq("opencode/big-pickle"));
        verify(opencodeClient, times(2))
                .sendMessage(eq("oc-sticky"), anyString(), anyString(), eq("opencode/ling-3.0-flash-fin-free"));
    }
}
