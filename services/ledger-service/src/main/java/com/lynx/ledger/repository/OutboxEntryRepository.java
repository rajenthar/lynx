package com.lynx.ledger.repository;

import com.lynx.ledger.domain.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEntryRepository extends JpaRepository<OutboxEntry, Long> {
}
