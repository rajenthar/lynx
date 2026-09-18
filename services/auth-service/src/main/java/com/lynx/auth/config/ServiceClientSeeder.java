package com.lynx.auth.config;

import com.lynx.auth.domain.ServiceClient;
import com.lynx.auth.repository.ServiceClientRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

  private record SeedClient(String clientId, String[] roles) {
  }

  private static final List<SeedClient> SEED_CLIENTS = List.of(
      new SeedClient("saga-orchestrator", new String[] {"internal-service"}),
      new SeedClient("account-service", new String[] {"internal-service"}),
      new SeedClient("transaction-service", new String[] {"internal-service"}));

  private final ServiceClientRepository serviceClientRepository;
  private final BCryptPasswordEncoder passwordEncoder;
  // Same value each caller's own application.yml reads as `service-token.client-secret`
  // — one shared env var rather than one per service, since every internal
  // caller here is trusted equally (ADR-007 Option C) and there's no
  // per-service secret rotation need yet. Never committed with a real
  // value; the placeholder default only matches across services when NONE
  // of them override it, which is exactly the local-dev case.
  private final String clientSecret;

  public ServiceClientSeeder(
      ServiceClientRepository serviceClientRepository, BCryptPasswordEncoder passwordEncoder,
      @Value("${lynx.service-client-secret:placeholder-not-a-real-secret}") String clientSecret) {
    this.serviceClientRepository = serviceClientRepository;
    this.passwordEncoder = passwordEncoder;
    this.clientSecret = clientSecret;
  }

  @Override
  public void run(String... args) {
    for (SeedClient seed : SEED_CLIENTS) {
      if (serviceClientRepository.findByClientId(seed.clientId()).isPresent()) {
        continue;
      }
      serviceClientRepository.save(new ServiceClient(
          seed.clientId(), passwordEncoder.encode(clientSecret), seed.roles(), Instant.now()));
      log.info("Seeded service_clients row for '{}' (local dev convenience — matches its own application.yml placeholder)",
          seed.clientId());
    }
  }
}
