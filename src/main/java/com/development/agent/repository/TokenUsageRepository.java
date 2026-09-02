package com.development.agent.repository;

import com.development.agent.entity.TokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {
    List<TokenUsage> findByUserIdOrderByCreatedAtDesc(Long userId);
    void deleteByUserId(Long userId);

    @Query("SELECT COALESCE(SUM(t.tokensInput + t.tokensOutput), 0) FROM TokenUsage t WHERE t.user.id = :userId")
    long sumTokensByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(t.tokensInput + t.tokensOutput), 0) FROM TokenUsage t")
    long sumAllTokens();

    @Query("SELECT t.modelName, COALESCE(SUM(t.tokensInput + t.tokensOutput), 0) FROM TokenUsage t GROUP BY t.modelName")
    List<Object[]> sumTokensByModel();

    @Query("SELECT t.modelName, COALESCE(SUM(t.tokensInput + t.tokensOutput), 0) FROM TokenUsage t WHERE t.user.id = :userId GROUP BY t.modelName")
    List<Object[]> sumTokensByModelForUser(@Param("userId") Long userId);

    @Query("SELECT t.user.id, COALESCE(SUM(t.tokensInput + t.tokensOutput), 0) FROM TokenUsage t WHERE t.user.id IN :userIds GROUP BY t.user.id")
    List<Object[]> sumTokensByUserIds(@Param("userIds") List<Long> userIds);

    /** Aggregated daily totals, [day, tokens], for all users since {@code since}. */
    @Query("SELECT CAST(t.createdAt AS date) AS day, COALESCE(SUM(t.tokensInput + t.tokensOutput), 0) " +
            "FROM TokenUsage t WHERE t.createdAt >= :since " +
            "GROUP BY CAST(t.createdAt AS date) ORDER BY CAST(t.createdAt AS date)")
    List<Object[]> sumTokensPerDaySince(@Param("since") LocalDateTime since);

    /** Aggregated daily totals, [day, tokens], for a single user since {@code since}. */
    @Query("SELECT CAST(t.createdAt AS date) AS day, COALESCE(SUM(t.tokensInput + t.tokensOutput), 0) " +
            "FROM TokenUsage t WHERE t.user.id = :userId AND t.createdAt >= :since " +
            "GROUP BY CAST(t.createdAt AS date) ORDER BY CAST(t.createdAt AS date)")
    List<Object[]> sumTokensPerDaySinceForUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
