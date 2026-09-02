package com.development.agent.repository;

import com.development.agent.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findByExternalId(String externalId);
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);
    void deleteByExternalId(String externalId);
    void deleteByUserId(Long userId);

    @Query("SELECT c.user.id, COUNT(c) FROM Conversation c WHERE c.user.id IN :userIds GROUP BY c.user.id")
    List<Object[]> countByUserIds(@Param("userIds") List<Long> userIds);

    @Query("SELECT COUNT(c) FROM Conversation c WHERE c.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);
}
