package io.chatbots.grammar.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

public interface AiInteractionRepository extends JpaRepository<AiInteraction, Long> {

    long countByCreatedAtAfterAndOutcome(LocalDateTime since, String outcome);

    @Query("SELECT AVG(a.latencyMs) FROM AiInteraction a WHERE a.createdAt > ?1 AND a.outcome = 'OK'")
    Double averageLatencySince(LocalDateTime since);

    @Modifying
    @Query("DELETE FROM AiInteraction a WHERE a.createdAt < ?1")
    int deleteOlderThan(LocalDateTime cutoff);
}
