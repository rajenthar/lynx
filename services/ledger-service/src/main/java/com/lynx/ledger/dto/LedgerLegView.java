package com.lynx.ledger.dto;

import com.lynx.ledger.domain.EntryType;
import java.math.BigDecimal;
import java.util.UUID;

/** Wire/response view of one ledger leg. */
public record LedgerLegView(
    EntryType entryType, UUID accountId, BigDecimal amount, String currency) {
}
