package com.lynx.ledger.dto;

import java.util.List;
import java.util.UUID;

/** Response returned by every phase method (hold/lock/settle/release) and by retries alike. */
public record LedgerPhaseResponse(UUID sagaId, List<LedgerLegView> legs) {
}
