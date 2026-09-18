package com.lynx.account.projection;

import com.lynx.account.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wraps a delegate recoverer (a {@code DeadLetterPublishingRecoverer} —
 * see {@code BeansConfig}) to ALSO record the failed event's id in {@code
 * processed_events}, right after the delegate has actually published it
 * to the dead-letter topic.
 *
 * <p>Deliberately best-effort for the id extraction ({@link
 * OutboxEventConsumer#extractEventIdOrNull}) — the delegate's own dead-letter
 * publish is the actual "don't lose this message" guarantee; this is only
 * "also avoid a duplicate DLT entry on top of that," so a record whose id
 * can't even be parsed just skips the marking step rather than failing
 * the whole recovery.
 *
 * <p>{@code accept} runs on Kafka's own error-handling thread, with no
 * transaction already open — a bare {@code markProcessedIfNew} call (a
 * {@code @Modifying} native query) throws {@code
 * InvalidDataAccessApiUsageException} without one. Wrapped in the SAME
 * {@link TransactionTemplate} {@link EventProjector} already uses for
 * exactly this reason, rather than {@code @Transactional} — this class is
 * constructed with {@code new} (see {@code BeansConfig}), not a
 * Spring-managed bean, so an annotation here would never actually be
 * proxied.
 */
public class ProcessedEventMarkingRecoverer implements ConsumerRecordRecoverer {

  private static final Logger log = LoggerFactory.getLogger(ProcessedEventMarkingRecoverer.class);

  private final ConsumerRecordRecoverer delegate;
  private final ProcessedEventRepository processedEventRepository;
  private final TransactionTemplate transactionTemplate;

  public ProcessedEventMarkingRecoverer(ConsumerRecordRecoverer delegate,
                                         ProcessedEventRepository processedEventRepository,
                                         TransactionTemplate transactionTemplate) {
    this.delegate = delegate;
    this.processedEventRepository = processedEventRepository;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Exception exception) {
    delegate.accept(record, exception);

    Object value = record.value();
    Long eventId = value instanceof String s ? OutboxEventConsumer.extractEventIdOrNull(s) : null;
    if (eventId == null) {
      log.warn("Dead-lettered a record but could not extract its eventId — it may be "
          + "re-processed (and re-dead-lettered) on redelivery: topic={}, partition={}, offset={}",
          record.topic(), record.partition(), record.offset());
      return;
    }
    transactionTemplate.executeWithoutResult(status -> processedEventRepository.markProcessedIfNew(eventId));
    log.warn("Dead-lettered eventId={} after exhausting retries (topic={}, partition={}, offset={}): {}",
        eventId, record.topic(), record.partition(), record.offset(), exception.getMessage());
  }
}
