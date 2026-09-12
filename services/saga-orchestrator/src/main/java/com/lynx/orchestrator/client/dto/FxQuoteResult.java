package com.lynx.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The pieces of {@code fx-rate-service}'s {@code GET /v1/fx/quotes}
 * response this service actually needs. {@code quoteId} is deliberately
 * NOT captured — nothing here correlates a quote back to the eventual
 * execution (see fx-rate-service-flows.html's known gaps); if that's ever
 * needed, add it here rather than re-deriving it some other way.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} — this record only
 * models the fields it uses, not the full response shape; fx-rate-service
 * adding a field later must never break deserialization here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxQuoteResult(BigDecimal rate, Instant expiresAt) {
}
