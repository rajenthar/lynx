package com.lynx.auth.config;

import com.lynx.auth.domain.ServiceClient;
import com.lynx.auth.repository.ServiceClientRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds every known internal caller's own service-client row on startup, if
 * missing — the exact {@code client-id}/{@code client-secret} each one's
 * own {@code application.yml} already hard-codes, so local end-to-end
 * wiring works without a manual seeding step once this service exists.
 * {@code account-service} and
 * {@code transaction-service} are seeded alongside {@code
 * saga-orchestrator} — each calls a downstream service
 * with its own service identity, ADR-007 Option C: {@code account-service}
 * and {@code saga-orchestrator} call {@code ledger-service};
 * {@code transaction-service} calls {@code saga-orchestrator} and {@code
 * account-service}'s recipient-resolution endpoint.
 *
 * <p>Done here rather than as SQL in the {@code V1} migration — a BCrypt
 * hash is salted and non-deterministic, so there's no single hash value to
 * write into a static migration file; hashing it once at startup with this
 * service's own {@link BCryptPasswordEncoder} bean is simpler than
 * generating and pasting a fixed hash by hand. Idempotent: only inserts a
 * row that doesn't already exist, so it's safe on every restart.
 */
@Component
public class ServiceClientSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(ServiceClientSeeder.class);

  private record SeedClient(String clientId, String clientSecret, String[] roles) {
  }

  private static final List<SeedClient> SEED_CLIENTS = List.of(
      new SeedClient("saga-orchestrator", "placeholder-not-a-real-secret", new String[] {"internal-service"}),
      new SeedClient("account-service", "placeholder-not-a-real-secret", new String[] {"internal-service"}),
      new SeedClient("transaction-service", "placeholder-not-a-real-secret", new String[] {"internal-service"}));

  private final ServiceClientRepository serviceClientRepository;
  private final BCryptPasswordEncoder passwordEncoder;

  public ServiceClientSeeder(
      ServiceClientRepository serviceClientRepository, BCryptPasswordEncoder passwordEncoder) {
    this.serviceClientRepository = serviceClientRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public void run(String... args) {
    for (SeedClient seed : SEED_CLIENTS) {
      if (serviceClientRepository.findByClientId(seed.clientId()).isPresent()) {
        continue;
      }
      serviceClientRepository.save(new ServiceClient(
          seed.clientId(), passwordEncoder.encode(seed.clientSecret()), seed.roles(), Instant.now()));
      log.info("Seeded service_clients row for '{}' (local dev convenience — matches its own application.yml placeholder)",
          seed.clientId());
    }
  }
}
