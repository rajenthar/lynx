package com.lynx.account.repository;

import com.lynx.account.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

  /**
   * The whole mechanism — one atomic statement,
   * not a separate exists-check followed by an insert (which would itself
   * be racy under concurrent consumers). Returns 1 if this event id was
   * genuinely new (go ahead and apply it), 0 if it was already recorded
   * (skip — this is a redelivery, not a new event). Order-independent by
   * design: works identically whether this event arrives before or after
   * some other event with a higher or lower id.
   */
  @Modifying
  @Query(value = "INSERT INTO processed_events (event_id, processed_at) "
      + "VALUES (:eventId, now()) ON CONFLICT (event_id) DO NOTHING",
      nativeQuery = true)
  int markProcessedIfNew(@Param("eventId") long eventId);
}
