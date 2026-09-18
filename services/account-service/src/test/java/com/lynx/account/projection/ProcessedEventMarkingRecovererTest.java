package com.lynx.account.projection;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.account.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ProcessedEventMarkingRecovererTest {

  /** A real TransactionTemplate over a mocked (interface) transaction manager — no real DB/transaction needed to prove this class's own dispatch logic. */
  private static TransactionTemplate fakeTransactionTemplate() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any()))
        .thenReturn(mock(TransactionStatus.class));
    return new TransactionTemplate(transactionManager);
  }

  @Test
  void delegatesFirstThenMarksTheExtractedEventIdAsProcessed() {
    ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
    ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    ProcessedEventMarkingRecoverer recoverer =
        new ProcessedEventMarkingRecoverer(delegate, processedEventRepository, fakeTransactionTemplate());
    ConsumerRecord<Object, Object> record = new ConsumerRecord<>(
        "lynx.public.outbox", 0, 5L, "key", "{\"id\":42,\"payload\":\"{}\"}");
    Exception failure = new RuntimeException("boom");

    recoverer.accept(record, failure);

    verify(delegate).accept(record, failure);
    verify(processedEventRepository).markProcessedIfNew(42L);
  }

  @Test
  void stillDelegatesEvenWhenTheEventIdCannotBeExtracted() {
    ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
    ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    ProcessedEventMarkingRecoverer recoverer =
        new ProcessedEventMarkingRecoverer(delegate, processedEventRepository, fakeTransactionTemplate());
    ConsumerRecord<Object, Object> record = new ConsumerRecord<>(
        "lynx.public.outbox", 0, 5L, "key", "not valid json");
    Exception failure = new RuntimeException("boom");

    recoverer.accept(record, failure);

    verify(delegate).accept(record, failure);
    verify(processedEventRepository, never()).markProcessedIfNew(anyLong());
  }
}
