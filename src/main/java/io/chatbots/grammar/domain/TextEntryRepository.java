package io.chatbots.grammar.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TextEntryRepository extends JpaRepository<TextEntry, Long> {

    @Query("SELECT t FROM TextEntry t JOIN FETCH t.chatUser WHERE t.id = ?1")
    Optional<TextEntry> findWithUser(Long id);

    Optional<TextEntry> findFirstByChatUserAndSourceMessageIdOrderByIdDesc(ChatUser chatUser, Integer sourceMessageId);

    /** Result languages this user ended up with, most used first. */
    @Query("SELECT t.targetLanguage FROM TextEntry t WHERE t.chatUser = ?1 GROUP BY t.targetLanguage ORDER BY COUNT(t) DESC")
    List<Language> targetLanguagesByUse(ChatUser chatUser);

    long countByCreatedAtAfter(LocalDateTime since);

    @Query("SELECT t.action, COUNT(t) FROM TextEntry t WHERE t.createdAt > ?1 AND t.action IS NOT NULL GROUP BY t.action")
    List<Object[]> countByActionSince(LocalDateTime since);

    @Modifying
    @Query("DELETE FROM TextEntry t WHERE t.createdAt < ?1")
    int deleteOlderThan(LocalDateTime cutoff);
}
