package com.lynx.fxrate.repository;

import com.lynx.fxrate.domain.FxExecution;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxExecutionRepository extends JpaRepository<FxExecution, Long> {

  Optional<FxExecution> findByExecutionId(UUID executionId);
}
