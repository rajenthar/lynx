package com.lynx.ledger.repository;

import com.lynx.ledger.domain.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

  List<LedgerEntry> findBySagaIdAndUserIdOrderByCreatedAtAsc(UUID sagaId, String userId);
}
