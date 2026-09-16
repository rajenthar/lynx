package com.lynx.transaction.service;

import com.lynx.common.error.BusinessRuleException;
import com.lynx.common.error.ErrorCode;
import com.lynx.common.error.NotFoundException;
import com.lynx.common.error.ValidationException;
import com.lynx.idempotency.IdempotencyKey;
import com.lynx.idempotency.SagaIds;
import com.lynx.transaction.client.AccountServiceClient;
import com.lynx.transaction.client.AccountServiceClient.ResolvedTransferAccounts;
import com.lynx.transaction.client.DownstreamRejectedException;
import com.lynx.transaction.client.DownstreamUnavailableException;
import com.lynx.transaction.client.SagaOrchestratorClient;
import com.lynx.transaction.client.SagaOrchestratorClient.SagaSummary;
import com.lynx.transaction.dto.TransactionDtos.CreateTransferRequest;
import com.lynx.transaction.dto.TransactionDtos.TransferAcceptedView;
import com.lynx.transaction.dto.TransactionDtos.TransferStatusView;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates a real end user's transfer request into
 * {@code saga-orchestrator}'s internal API — the real
 * caller chain ADR-004 originally described (client {@code
 * Idempotency-Key} → deterministic {@code sagaId}), its first actual
 * caller. Owns no durable state itself: every fact this service ever
 * returns is read straight through from {@code saga-orchestrator} or
 * {@code account-service}.
 */
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  private final SagaOrchestratorClient sagaOrchestratorClient;
  private final AccountServiceClient accountServiceClient;

  public TransactionService(SagaOrchestratorClient sagaOrchestratorClient,
                             AccountServiceClient accountServiceClient) {
    this.sagaOrchestratorClient = sagaOrchestratorClient;
    this.accountServiceClient = accountServiceClient;
  }

  public TransferAcceptedView createTransfer(String userId, String idempotencyKeyHeader,
                                              CreateTransferRequest request) {
    UUID sagaId = SagaIds.deriveSagaId(
        userId, IdempotencyKey.fromClientHeader(idempotencyKeyHeader));
    log.info("Transfer requested by userId={}, derived sagaId={}", userId, sagaId);

    ResolvedTransferAccounts accounts = withDownstreamTranslation(() -> accountServiceClient
        .resolveTransferAccounts(userId, request.fromCurrency(), request.recipientUserId(), request.toCurrency()));

    SagaSummary saga = withDownstreamTranslation(() -> sagaOrchestratorClient.createSaga(
        sagaId, userId, accounts.senderAccountId(), accounts.recipientAccountId(),
        request.amount(), request.fromCurrency(), request.toCurrency()));
    return new TransferAcceptedView(saga.sagaId(), saga.status());
  }

  public TransferStatusView getTransfer(UUID sagaId, String userId) {
    SagaSummary saga = withDownstreamTranslation(() -> sagaOrchestratorClient.getSaga(sagaId, userId));
    return new TransferStatusView(saga.sagaId(), saga.status(), saga.failureReason());
  }

  /**
   * Shared translation for every downstream call this service makes
   * (account resolution, saga creation, saga lookup) — a {@link
   * DownstreamUnavailableException} always becomes a retryable 503; a
   * {@link DownstreamRejectedException}'s 404 becomes a client-facing 404
   * (no account in that currency, a saga that doesn't exist, or isn't
   * this user's own — {@code account-service}'s own error message already
   * distinguishes sender from recipient, so it's passed straight
   * through), anything else a 400.
   */
  private <T> T withDownstreamTranslation(java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (DownstreamRejectedException e) {
      if (e.statusCode() == 404) {
        throw new NotFoundException(e.getMessage());
      }
      throw new ValidationException(e.getMessage());
    } catch (DownstreamUnavailableException e) {
      throw new BusinessRuleException(ErrorCode.DOWNSTREAM_UNAVAILABLE, e.getMessage());
    }
  }
}
