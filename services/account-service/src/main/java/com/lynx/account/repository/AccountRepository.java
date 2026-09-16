package com.lynx.account.repository;

import com.lynx.account.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

  List<Account> findByUserId(String userId);

  /** Backs the one-account-per-currency rule — checked before {@code createAccount} inserts. */
  Optional<Account> findByUserIdAndCurrency(String userId, String currency);

  /**
   * {@code PESSIMISTIC_WRITE} — {@link com.lynx.account.projection.EventProjector}
   * uses this, never the plain {@code findById}, before mutating {@code
   * available}/{@code held}: a second Kafka consumer instance (a future
   * scale-out) applying an event for the SAME account concurrently must
   * serialize on this row, not silently interleave two read-modify-writes.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM Account a WHERE a.id = :id")
  Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
