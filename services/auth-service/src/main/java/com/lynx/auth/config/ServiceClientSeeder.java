package com.lynx.auth.config;

import com.lynx.auth.domain.ServiceClient;
import com.lynx.auth.repository.ServiceClientRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code saga-orchestrator}'s own service-client row on startup, if
 * missing — the exact {@code client-id}/{@code client-secret} its own
 * {@code application.yml} already hard-codes
 * ({@code saga-orchestrator} / {@code placeholder-not-a-real-secret}), so
 * local end-to-end wiring works without a manual seeding step once this
 * service exists (other-docs/11 Decision 3).
 *
 * <p>Done here rather than as SQL in the {@code V1} migration — a BCrypt
 * hash is salted and non-deterministic, so there's no single hash value to
 * write into a static migration file; hashing it once at startup with this
 * service's own {@link BCryptPasswordEncoder} bean is simpler than
 * generating and pasting a fixed hash by hand. Idempotent: only inserts if
 * the row doesn't already exist, so it's safe on every restart.
 */
@Component
public class ServiceClientSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(ServiceClientSeeder.class);

  private static final String SEED_CLIENT_ID = "saga-orchestrator";
  private static final String SEED_CLIENT_SECRET = "placeholder-not-a-real-secret";
  private static final String[] SEED_ROLES = {"internal-service"};

  private final ServiceClientRepository serviceClientRepository;
  private final BCryptPasswordEncoder passwordEncoder;

  public ServiceClientSeeder(
      ServiceClientRepository serviceClientRepository, BCryptPasswordEncoder passwordEncoder) {
    this.serviceClientRepository = serviceClientRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public void run(String... args) {
    if (serviceClientRepository.findByClientId(SEED_CLIENT_ID).isPresent()) {
      return;
    }
    serviceClientRepository.save(new ServiceClient(
        SEED_CLIENT_ID, passwordEncoder.encode(SEED_CLIENT_SECRET), SEED_ROLES, Instant.now()));
    log.info("Seeded service_clients row for '{}' (local dev convenience — matches its own application.yml placeholder)",
        SEED_CLIENT_ID);
  }
}
