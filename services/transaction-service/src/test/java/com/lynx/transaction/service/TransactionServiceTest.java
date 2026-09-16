package com.lynx.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lynx.common.error.BusinessRuleException;
import com.lynx.common.error.ErrorCode;
import com.lynx.common.error.NotFoundException;
import com.lynx.transaction.client.AccountServiceClient;
import com.lynx.transaction.client.AccountServiceClient.ResolvedTransferAccounts;
import com.lynx.transaction.client.DownstreamRejectedException;
import com.lynx.transaction.client.DownstreamUnavailableException;
import com.lynx.transaction.client.SagaOrchestratorClient;
import com.lynx.transaction.dto.TransactionDtos.CreateTransferRequest;
import com.lynx.transaction.dto.TransactionDtos.TransferAcceptedView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AccountServiceClient}/{@link SagaOrchestratorClient} are concrete
 * classes — hand-rolled test doubles, not Mockito mocks, same reasoning as
 * every other service's client tests in this project (Byte Buddy/JDK 25).
 */
class TransactionServiceTest {

  private static final class FakeAccountServiceClient extends AccountServiceClient {
    ResolvedTransferAccounts onResolve;
    RuntimeException resolveFailure;

    FakeAccountServiceClient() {
      super(null, null, null);
    }

    @Override
    public ResolvedTransferAccounts resolveTransferAccounts(
        String senderUserId, String senderCurrency, String recipientUserId, String recipientCurrency) {
      if (resolveFailure != null) {
        throw resolveFailure;
      }
      return onResolve;
    }
  }

  private static class FakeSagaOrchestratorClient extends SagaOrchestratorClient {
    SagaSummary onCreate;
    RuntimeException createFailure;
    UUID lastSagaId;

    FakeSagaOrchestratorClient() {
      super(null, null, null);
    }

    @Override
    public SagaSummary createSaga(UUID sagaId, String userId, UUID fromAccountId, UUID toAccountId,
                                   BigDecimal amount, String fromCurrency, String toCurrency) {
      lastSagaId = sagaId;
      if (createFailure != null) {
        throw createFailure;
      }
      // Echoes the REAL derived sagaId back, exactly like the real
      // saga-orchestrator would (createSaga's response always reflects
      // whatever sagaId the caller supplied) — onCreate only supplies the
      // status/failureReason/timestamps a test cares about.
      return new SagaSummary(sagaId, onCreate.status(), onCreate.failureReason(),
          onCreate.createdAt(), onCreate.updatedAt());
    }
  }

  private FakeAccountServiceClient accountServiceClient;
  private FakeSagaOrchestratorClient sagaOrchestratorClient;
  private TransactionService transactionService;

  @BeforeEach
  void setUp() {
    accountServiceClient = new FakeAccountServiceClient();
    sagaOrchestratorClient = new FakeSagaOrchestratorClient();
    transactionService = new TransactionService(sagaOrchestratorClient, accountServiceClient);
  }

  @Test
  void createTransferResolvesBothAccountsAndCreatesTheSagaWithADeterministicSagaId() {
    UUID fromAccountId = UUID.randomUUID();
    UUID toAccountId = UUID.randomUUID();
    accountServiceClient.onResolve = new ResolvedTransferAccounts(fromAccountId, toAccountId);
    sagaOrchestratorClient.onCreate = new SagaOrchestratorClient.SagaSummary(
        UUID.randomUUID(), "HOLDING", null, Instant.now(), Instant.now());

    String idempotencyKey = UUID.randomUUID().toString();
    TransferAcceptedView view = transactionService.createTransfer(
        "user-1", idempotencyKey,
        new CreateTransferRequest("recipient-1", "SGD", "USD", new BigDecimal("50.00")));

    assertThat(view.status()).isEqualTo("HOLDING");

    // Same (userId, Idempotency-Key) must always derive the same sagaId —
    // the entire point of this service (ADR-004's real chain).
    UUID expectedSagaId = com.lynx.idempotency.SagaIds.deriveSagaId(
        "user-1", com.lynx.idempotency.IdempotencyKey.fromClientHeader(idempotencyKey));
    assertThat(view.sagaId()).isEqualTo(expectedSagaId);
  }

  @Test
  void createTransferIs404WhenTheCallerHasNoAccountInTheFromCurrency() {
    accountServiceClient.resolveFailure =
        new DownstreamRejectedException(404, "No SGD account found for userId=user-1");

    assertThatThrownBy(() -> transactionService.createTransfer(
        "user-1", UUID.randomUUID().toString(),
        new CreateTransferRequest("recipient-1", "SGD", "USD", new BigDecimal("50.00"))))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void createTransferIs404WhenTheRecipientHasNoAccountInTheToCurrency() {
    accountServiceClient.resolveFailure =
        new DownstreamRejectedException(404, "Recipient has no USD account: userId=recipient-1");

    assertThatThrownBy(() -> transactionService.createTransfer(
        "user-1", UUID.randomUUID().toString(),
        new CreateTransferRequest("recipient-1", "SGD", "USD", new BigDecimal("50.00"))))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void createTransferTranslatesADownstreamUnavailableSagaCreationIntoARetryable503() {
    accountServiceClient.onResolve = new ResolvedTransferAccounts(UUID.randomUUID(), UUID.randomUUID());
    sagaOrchestratorClient.createFailure = new DownstreamUnavailableException("circuit open");

    assertThatThrownBy(() -> transactionService.createTransfer(
        "user-1", UUID.randomUUID().toString(),
        new CreateTransferRequest("recipient-1", "SGD", "USD", new BigDecimal("50.00"))))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE));
  }

  @Test
  void getTransferProxiesTheSagaOrchestratorsOwnStatus() {
    UUID sagaId = UUID.randomUUID();
    sagaOrchestratorClient.onCreate = null;
    FakeSagaOrchestratorClient customGet = new FakeSagaOrchestratorClient() {
      @Override
      public SagaSummary getSaga(UUID id, String userId) {
        return new SagaSummary(id, "SETTLED", null, Instant.now(), Instant.now());
      }
    };
    TransactionService serviceWithCustomGet = new TransactionService(customGet, accountServiceClient);

    var view = serviceWithCustomGet.getTransfer(sagaId, "user-1");

    assertThat(view.sagaId()).isEqualTo(sagaId);
    assertThat(view.status()).isEqualTo("SETTLED");
  }
}
