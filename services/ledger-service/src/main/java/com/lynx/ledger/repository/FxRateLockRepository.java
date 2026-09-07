package com.lynx.ledger.repository;

import com.lynx.ledger.domain.FxRateLock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateLockRepository extends JpaRepository<FxRateLock, Long> {

  Optional<FxRateLock> findBySagaIdAndUserId(UUID sagaId, String userId);
}
