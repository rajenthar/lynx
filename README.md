# Lynx — Multi-Currency Ledger Engine

A production-grade distributed ledger system for multi-currency transfers with atomic saga orchestration, event-driven CQRS, and comprehensive observability.

---

## ⚡ What Lynx Does

1. **Accepts transfer requests** across currencies (SGD → EUR, etc.)
2. **Executes atomic 3-step saga** (hold → lock rate → settle)
3. **Records in immutable ledger** with 4-leg double-entry bookkeeping
4. **Guarantees exactly-once delivery** via idempotency-key
5. **Recovers from crashes** automatically (recovery worker)
6. **Provides observability** via OTel traces, Grafana dashboards

**Transfer Latency:** P95 < 1.2 seconds  
**Success Rate:** > 99.5%  
**Duplicate Rate:** 0% (guaranteed by idempotency)

---

## 🏗️ Architecture

### Write Side (Single Source of Truth)
- **ledger-service**: Append-only immutable ledger (Postgres SERIALIZABLE)
- **Outbox pattern**: Events published atomically with ledger writes
- **Debezium CDC**: Reads Postgres WAL, publishes to Kafka

### Read Side (Optimized Views)
- **account-service**: Balance projections (cached, eventual consistency)
- **reporting-service**: Transaction history (denormalized, MongoDB)
- **audit-service**: Compliance trail (WORM, immutable)
- **reconciliation-service**: Data integrity verification

### Orchestration
- **saga-orchestrator**: 3-step saga with automatic recovery
- **transaction-service**: Idempotency and lifecycle management
- **recovery-worker**: Auto-resume stuck sagas (< 15 min SLA)

---

## 📊 21 Modules

### Phase 1: Deployed (15 services)
- **shared/** (6 libraries): Money type, security, events, telemetry
- **services/api-gateway**: JWT validation, rate-limiting
- **services/auth-service**: RS256 JWT issuance
- **services/ledger-service**: ⭐ Crown jewel — append-only ledger
- **services/account-service**: CQRS read model for balances
- **services/fx-rate-service**: FX quotes and locking
- **services/saga-orchestrator**: 3-step transfer orchestration
- **services/transaction-service**: Thin lifecycle manager
- **services/fraud-detection-service**: Async velocity checks
- **services/limits-service**: Sliding-window rate limits
- **services/notification-service**: Email, webhook, push
- **services/audit-service**: Immutable compliance log
- **services/reporting-service**: CQRS read projections
- **services/reconciliation-service**: Data drift detection
- **services/admin-service**: Maker-checker reversals

### Phase 2: Planned (6 services - skeleton only)
- **kyc-service**: Identity verification
- **fee-service**: Fee + tax calculation
- **settlement-service**: SWIFT/SEPA simulation
- **reference-data-service**: Currencies, calendars
- **scheduler-service**: Distributed cron
- **config-service**: Dynamic runtime config

---

## 🛠️ Tech Stack

| Component | Technology |
|-----------|-----------|
| **Language** | Java 21 (records, sealed classes, virtual threads) |
| **Framework** | Spring Boot 3.3+ |
| **Build** | Maven multi-module |
| **Database** | Postgres 17 (SERIALIZABLE) |
| **Cache** | Redis |
| **Streaming** | Redpanda (Kafka-compatible) |
| **CDC** | Debezium Connect |
| **Docs DB** | MongoDB |
| **Observability** | OpenTelemetry → Grafana |
| **Deploy** | Docker on Linux |

---

## 🚀 Quick Start

### Build the monorepo
```bash
mvn clean install
```

### Run locally (with docker-compose)
```bash
cd infra
docker-compose -f docker-compose.local.yml up -d
```

### Run tests
```bash
mvn test
```

---

## 📁 Directory Structure

```
lynx/
├── pom.xml                         ← Maven aggregator (21 modules)
├── shared/                         ← Shared libraries
│   ├── lynx-common/               ← Errors, pagination, base entities
│   ├── lynx-money/                ← Money type, BigDecimal math
│   ├── lynx-security/             ← JWT verifier, correlation-ID filter
│   ├── lynx-idempotency/          ← Idempotency-Key middleware
│   ├── lynx-events/               ← Kafka event DTOs
│   └── lynx-telemetry/            ← OTel setup, structured logging
├── services/                       ← 21 microservices
│   ├── api-gateway/
│   ├── auth-service/
│   ├── ledger-service/            ← ⭐ Core service
│   ├── account-service/
│   ├── saga-orchestrator/
│   ├── [12 more Phase 1 services]
│   └── [6 Phase 2 skeleton services]
├── infra/                         ← Infrastructure
│   ├── docker-compose.local.yml
│   ├── redpanda/                  ← Streaming config
│   ├── debezium/                  ← CDC connector
│   ├── otel/                      ← Observability collector
│   └── migrations/                ← Database migrations
├── docs/                          ← Architecture records
│   ├── ADR-001.md through ADR-006.md
│   └── sequence-diagrams/
├── contracts/                     ← API specifications
│   ├── openapi/                   ← /v1 REST specs
│   └── events/                    ← Event schemas
├── frontend/                      ← Next.js applications
│   ├── lynx-dashboard/
│   └── lynx-admin/
└── .editorconfig                  ← Code style standards
```

---

## 🧪 Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests (requires docker-compose)
```bash
mvn test -Pintegration
```

### Key Test Scenarios
- ✅ Happy path: SGD → EUR transfer
- ✅ Idempotency: Retry with same key → same response
- ✅ Compensation: Failure → automatic hold release
- ✅ Ledger integrity: sum(DR) = sum(CR) always

---

## 📊 Performance Targets

| Metric | Target |
|--------|--------|
| POST /v1/transfers P95 | < 1.2s |
| Ledger write | < 50ms |
| Saga execution | < 800ms |
| Transfer success rate | > 99.5% |
| Duplicate rate | 0% |
| Recovery SLA | < 15 min |

---

## 🔐 Security

- **RS256 JWT** with JWKS caching
- **RBAC + ABAC** authorization
- **Correlation-ID** for request tracing
- **Maker-checker** for admin reversals
- **Immutable audit log** (WORM)
- **Idempotency** prevents replay attacks

---

## 📚 Learning Outcomes

This project covers:
- Distributed saga orchestration (3-step, compensating)
- Event-driven architecture (CQRS, CDC, streaming)
- Database transactions (SERIALIZABLE, idempotency)
- Observability (OTel, correlation IDs, distributed tracing)
- Financial domain concepts (double-entry bookkeeping, FX, holds)
- Production patterns (recovery, resilience, monitoring)

---

## 📖 Resources

- `docs/ADR-*.md` — Architecture Decision Records
- `docs/sequence-diagrams/` — Transfer flow diagrams
- `archi/` — Visual architecture documentation
- `contracts/openapi/` — API specifications

---

## Development Phases

- **Phase 0** ✅ Foundation (monorepo, ADRs, shared libs)
- **Phase 1** 🚧 Core services (ledger, saga, account, auth, etc.)
- **Phase 2** 📋 Infrastructure (Debezium, topics, observability)
- **Phase 3** 📋 Phase 2 service scaffolds
- **Phase 4** 📋 Integration tests and live demo
