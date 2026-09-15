package com.lynx.ledger.repository;

import com.lynx.ledger.domain.AccountBalance;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Two atomic, single-statement operations — the whole mechanism behind
 * other-docs/12 Decision 4's fix. Nothing here ever reads a balance and
 * then separately decides in Java; the SQL statement itself IS the
 * decision, so there's no window for a concurrent writer to interleave.
 */
public interface AccountBalanceRepository extends JpaRepository<AccountBalance, AccountBalance.Id> {

  /**
   * Unconditional adjust (credit OR debit) — used for every leg except a
   * real account's {@code HOLD_DR} (see {@link #debitIfSufficient}).
   * {@code ON CONFLICT DO UPDATE} both creates the row on an account's
   * first-ever movement AND adjusts it on every later one, in one
   * statement — a system account (allowed to run negative) and an
   * ordinary credit both go through here identically.
   */
  @Modifying
  @Query(value = "INSERT INTO account_balances (account_id, currency, balance) "
      + "VALUES (:accountId, :currency, :delta) "
      + "ON CONFLICT (account_id, currency) DO UPDATE "
      + "SET balance = account_balances.balance + :delta",
      nativeQuery = true)
  void adjustUnconditionally(
      @Param("accountId") UUID accountId, @Param("currency") String currency, @Param("delta") BigDecimal delta);

  /**
   * The guarded debit — other-docs/08 Decision 23 / other-docs/12
   * Decision 4. One conditional {@code UPDATE}: the {@code WHERE balance
   * >= :amount} clause IS the insufficient-funds check, evaluated
   * atomically against whatever the row's current value is at the moment
   * this statement acquires its row lock — not a value read moments
   * earlier by this same call. Returns 0 rows affected — treated as
   * insufficient funds — for BOTH "balance too low" and "no row yet"
   * (an account that has literally never been funded), no separate case
   * needed for either.
   */
  @Modifying
  @Query(value = "UPDATE account_balances SET balance = balance - :amount "
      + "WHERE account_id = :accountId AND currency = :currency AND balance >= :amount",
      nativeQuery = true)
  int debitIfSufficient(
      @Param("accountId") UUID accountId, @Param("currency") String currency, @Param("amount") BigDecimal amount);
}
