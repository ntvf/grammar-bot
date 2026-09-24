package io.chatbots.grammar.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ChatUserRepository extends JpaRepository<ChatUser, Long> {

    Optional<ChatUser> findByChatId(Long chatId);

    long countByOnboardedTrue();

    long countByLastActiveAtAfter(LocalDateTime since);

    @Query("SELECT u.mode, COUNT(u) FROM ChatUser u GROUP BY u.mode ORDER BY COUNT(u) DESC")
    List<Object[]> countByMode();

    @Query("SELECT u.targetLanguage, COUNT(u) FROM ChatUser u GROUP BY u.targetLanguage ORDER BY COUNT(u) DESC")
    List<Object[]> countByTargetLanguage();

    @Query("SELECT u.source, COUNT(u) FROM ChatUser u WHERE u.source IS NOT NULL GROUP BY u.source ORDER BY COUNT(u) DESC")
    List<Object[]> countBySource();
}
