package com.lynx.ledger.repository;

import com.lynx.ledger.domain.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

  List<LedgerEntry> findBySagaIdAndUserIdOrderByCreatedAtAsc(UUID sagaId, String userId);

  /**
   * The per-account audit trail (other-docs/12) — every leg this account
   * has ever appeared in, newest first. Backed by {@code
   * idx_ledger_account_id (account_id, created_at)} so this stays fast
   * regardless of how large the {@code ledger} table grows overall.
   */
  List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);
}
