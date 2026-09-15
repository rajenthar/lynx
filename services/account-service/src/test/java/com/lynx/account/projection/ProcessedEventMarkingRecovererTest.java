package com.lynx.account.projection;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.lynx.account.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

class ProcessedEventMarkingRecovererTest {

  @Test
  void delegatesFirstThenMarksTheExtractedEventIdAsProcessed() {
    ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
    ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    ProcessedEventMarkingRecoverer recoverer =
        new ProcessedEventMarkingRecoverer(delegate, processedEventRepository);
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
        new ProcessedEventMarkingRecoverer(delegate, processedEventRepository);
    ConsumerRecord<Object, Object> record = new ConsumerRecord<>(
        "lynx.public.outbox", 0, 5L, "key", "not valid json");
    Exception failure = new RuntimeException("boom");

    recoverer.accept(record, failure);

    verify(delegate).accept(record, failure);
    verify(processedEventRepository, never()).markProcessedIfNew(anyLong());
  }
}
