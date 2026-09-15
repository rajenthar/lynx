package com.lynx.ledger.dto;

import com.lynx.ledger.domain.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row in an account's history (other-docs/12) — unlike {@link
 * LedgerLegView} (a single saga's two legs, where the saga context is
 * already known from the URL), this carries {@code sagaId} and {@code
 * createdAt} since a history listing spans many different sagas/deposits.
 */
public record LedgerHistoryEntryView(
    UUID sagaId, EntryType entryType, UUID accountId,
    BigDecimal amount, String currency, Instant createdAt) {
}
