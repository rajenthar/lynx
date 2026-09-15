package com.lynx.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A materialized running balance for one (account, currency) pair — see
 * the {@code account_balances} table's own migration comment for the full
 * rationale (other-docs/12 Decision 4). Mutated ONLY through {@link
 * com.lynx.ledger.repository.AccountBalanceRepository}'s two native,
 * single-statement operations — never loaded, mutated in Java, and saved
 * back the ordinary JPA way, since the whole point is one atomic
 * conditional SQL statement per adjustment, not a read-modify-write.
 * This entity class exists mainly so Hibernate validates the table shape
 * against this mapping at startup ({@code ddl-auto: validate}).
 */
@Entity
@Table(name = "account_balances")
public class AccountBalance {

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "currency")
    private String currency;

    protected Id() {
      // JPA
    }

    public Id(UUID accountId, String currency) {
      this.accountId = accountId;
      this.currency = currency;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Id other)) {
        return false;
      }
      return Objects.equals(accountId, other.accountId) && Objects.equals(currency, other.currency);
    }

    @Override
    public int hashCode() {
      return Objects.hash(accountId, currency);
    }
  }

  @EmbeddedId
  private Id id;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal balance;

  protected AccountBalance() {
    // JPA
  }
}
