package com.posplatform.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Pending events for a relay process (oldest first). */
    @Query("SELECT e FROM OutboxEvent e WHERE e.processedAt IS NULL ORDER BY e.id ASC")
    List<OutboxEvent> findPending();
}
