package io.chatbots.grammar.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;

public interface DailyUsageRepository extends JpaRepository<DailyUsage, DailyUsage.Key> {

    /** Atomically increments today's counter and returns the new value. */
    @Query(nativeQuery = true, value = """
        INSERT INTO daily_usage (chat_id, usage_date, request_count)
        VALUES (?1, ?2, 1)
        ON CONFLICT (chat_id, usage_date)
        DO UPDATE SET request_count = daily_usage.request_count + 1
        RETURNING request_count
        """)
    int incrementAndGet(Long chatId, LocalDate date);

    @Modifying
    @Query("DELETE FROM DailyUsage d WHERE d.usageDate < ?1")
    int deleteOlderThan(LocalDate cutoff);
}
