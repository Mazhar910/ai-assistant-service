package com.development.agent.repository;

import com.development.agent.entity.ChatMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    List<ChatMessageEntity> findByConversationExternalIdOrderByCreatedAtAsc(String externalId);
    List<ChatMessageEntity> findByConversationExternalIdOrderByCreatedAtDesc(String externalId, Pageable pageable);
    long countByConversationIdAndRole(Long conversationId, String role);
    void deleteByConversationId(Long conversationId);
    void deleteByConversationExternalId(String externalId);

    /** Bulk delete (single statement) for all messages of a set of conversations. */
    @Modifying
    @Query("DELETE FROM ChatMessageEntity m WHERE m.conversation.id IN :conversationIds")
    int bulkDeleteByConversationIds(@Param("conversationIds") Collection<Long> conversationIds);
}
