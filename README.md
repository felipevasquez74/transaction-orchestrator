# TumiPay Transaction Orchestrator

> **Microservice** | Java 17 | Spring Boot 3.2 | Hexagonal Architecture | MySQL 8 | Redis 7 | Docker

A production-ready payment transaction orchestration microservice for the TumiPay Fintech Platform. Receives authenticated transaction requests, validates them with multi-layer domain checks, persists them with idempotency guarantees, and routes them to payment provider adapters through a defense-in-depth security model.

---

## Table of Contents

- [Project Overview](#project-overview)
- [Architecture Diagram](#architecture-diagram)
- [Security Architecture](#security-architecture)
- [Tech Stack](#tech-stack)
- [Hexagonal Architecture](#hexagonal-architecture)
- [Package Structure](#package-structure)
- [Design Patterns Applied](#design-patterns-applied)
- [API Contract](#api-contract)
- [Error Codes Dictionary](#error-codes-dictionary)
- [Database Schema](#database-schema)
- [Observability Stack](#observability-stack)
- [CI/CD Pipeline](#cicd-pipeline)
- [Code Quality Strategy](#code-quality-strategy)
- [How to Run](#how-to-run)
- [Architectural Decisions](#architectural-decisions)
- [Assumptions](#assumptions)
- [Identified Risks](#identified-risks)
- [Next Steps](#next-steps)

---

## Project Overview

The **Transaction Orchestrator** is a central hub in the TumiPay payment infrastructure. It acts as a mediator between merchant/client systems and payment providers (PSE, credit cards, digital wallets like Nequi/Daviplata, cash networks like Efecty, and real-time payment rails like Bre-B).

### Core Responsibilities

| Responsibility | Description |
|---|---|
| **Authentication** | API key validation with constant-time comparison and optional IP whitelist per key |
| **Validation** | Three-layer validation: Bean Validation → Domain rules → SSRF guard |
| **Idempotency** | Distributed Redis lock + DB constraint + Optimistic Locking (`@Version`) |
| **Persistence** | Persists every transaction BEFORE sending to provider (guaranteed audit trail) |
| **Routing** | Selects the correct payment provider adapter at runtime (Strategy Pattern) |
| **Resilience** | CircuitBreaker → Retry with exponential backoff |
| **Observability** | Structured JSON logging, MDC correlation IDs, Spring Boot Actuator |

---

## Architecture Diagram

```
                         ┌──────────────────────────────────────────────────────────────┐
                         │             TumiPay Transaction Orchestrator                  │
                         │                                                              │
  ┌────────────────┐     │  ┌─────────────────────────────────────────────────────────┐ │
  │  Client System │────▶│  │                   SECURITY FILTER CHAIN                 │ │
  │  (Merchant API)│     │  │                                                         │ │
  └────────────────┘     │  │  CorrelationIdFilter (Order 1)                          │ │
                         │  │    └─ Correlation ID → MDC                              │ │
  ┌────────────────┐     │  │    └─ Client IP → MDC                                   │ │
  │  Swagger UI    │────▶│  │                                                         │ │
  │  (OpenAPI)     │     │  │  ApiKeyAuthFilter                                       │ │
  └────────────────┘     │  │    └─ Constant-time XOR key validation                  │ │
                         │  │    └─ Optional IP whitelist per API key                 │ │
                         │  └─────────────────────────────────────────────────────────┘ │
                         │                           │                                  │
                         │                           ▼                                  │
                         │  ┌─────────────────────────────────────────────────────────┐ │
                         │  │              HTTP Inbound Adapter                        │ │
                         │  │  REST Controller + Bean Validation + MapStruct DTOs      │ │
                         │  └───────────────────────┬─────────────────────────────────┘ │
                         │                          │                                   │
                         │                          ▼                                   │
                         │  ┌─────────────────────────────────────────────────────────┐ │
                         │  │              APPLICATION CORE                            │ │
                         │  │                                                         │ │
                         │  │  CreateTransactionService                               │ │
                         │  │    1. Domain Validation (+ SSRF guard + amount limits)  │ │
                         │  │    2. Redis SET NX idempotency lock (atomic)            │ │
                         │  │    3. DB duplicate check (fallback)                     │ │
                         │  │    4. Persist PENDING (audit trail)                     │ │
                         │  │    5. Provider resolution (Strategy Pattern)            │ │
                         │  │    6. Provider call (CircuitBreaker → Retry)            │ │
                         │  │    7. Persist final status + update idempotency cache   │ │
                         │  └───────────────────────┬─────────────────────────────────┘ │
                         │                          │                                   │
                         │         ┌────────────────┴───────────────┐                  │
                         │         ▼                                ▼                  │
                         │  ┌──────────────┐           ┌────────────────────────┐     │
                         │  │   MySQL 8    │           │  Redis 7               │     │
                         │  │  (Primary DB)│           │  Idempotency + Cache   │     │
                         │  └──────────────┘           └────────────────────────┘     │
                         └──────────────────────────────────────────────────────────────┘
```

### Transaction Flow (7 Steps)

```
Client → POST /api/v1/transactions  (X-API-Key: <key>)
             │
             ▼
┌────────────────────────────────────────────────────┐
│  Security Filter Chain                             │
│  ① Correlation ID + client IP → MDC               │
│  ② API key validation (constant-time XOR)          │
│  ③ IP whitelist check (optional, per key)          │
└────────────────────┬───────────────────────────────┘
                     │  HTTP 401 on failure
                     ▼
┌────────────────────────────────────────────────────┐
│  Bean Validation (@Valid on DTO)                   │
│  • Required fields, length constraints             │
│  • Currency/Country format patterns                │
└────────────────────┬───────────────────────────────┘
                     │  HTTP 422 on failure
                     ▼
┌────────────────────────────────────────────────────┐
│  CreateTransactionService                          │
│                                                    │
│  Step 1 ─ TransactionDomainValidator               │
│    • amount > 0 AND ≤ MAX_AMOUNT_CENTS             │
│    • ISO 4217 currency whitelist                   │
│    • ISO 3166-1 country whitelist                  │
│    • URL format check                              │
│    • SSRF guard on webhook/redirect URLs           │
│      (blocks localhost, RFC-1918, AWS metadata)    │
│    • Customer field validation                     │
│    • Collects ALL violations (no early exit)       │
│                                                    │
│  Step 2 ─ Redis SET NX idempotency lock            │
│    • Atomic: prevents concurrent duplicates        │
│    • TTL 24h; returns existing if already locked   │
│                                                    │
│  Step 3 ─ DB duplicate check (fallback)            │
│    • Unique constraint on client_transaction_id    │
│                                                    │
│  Step 4 ─ Persist PENDING                          │
│    • Audit trail guaranteed before provider call   │
│                                                    │
│  Step 5 ─ Provider Resolution (Strategy Pattern)  │
│    • Iterates PaymentProviderPort beans            │
│    • Calls supports(paymentMethodId)               │
│                                                    │
│  Step 6 ─ Provider Call (Resilience4j)             │
│    CircuitBreaker (50% failure → 30s open)         │
│      → Retry (3 attempts, exp. backoff 1→2→4s)    │
│                                                    │
│  Step 7 ─ Persist final status                     │
│    • @Version optimistic locking prevents          │
│      concurrent status overwrites                  │
│    • Update Redis idempotency cache                │
│                                                    │
│  Step 8 ─ Metrics (Golden Signals)                 │
│    • Latency histogram (p50/p95/p99)               │
│    • Traffic counters (by method, currency)        │
│    • Error counters (by type)                      │
└────────────────────┬───────────────────────────────┘
                     │
                     ▼  HTTP 201 Created
```

---

## Security Architecture

TumiPay implements **defense-in-depth** across five independent security layers:

```
Internet
    │
    ▼  Layer 1: Network
   [Load Balancer / NGINX]
    │
    ▼  Layer 2: Correlation (CorrelationIdFilter)
   Correlation ID generated / forwarded → MDC
   Client IP resolved → MDC
    │
    ▼  Layer 3: Authentication (ApiKeyAuthFilter)
   Constant-time XOR comparison → prevents timing attacks
   IP whitelist per key (optional, defense-in-depth)
   Same HTTP 401 for wrong key AND wrong IP (no oracle)
    │
    ▼  Layer 4: Domain Validation
   Amount bounds (max configurable per deployment)
   SSRF guard on webhook/redirect URLs:
     • Scheme allow-list (http, https only)
     • Hostname block-list (localhost, metadata.*...)
     • Private IP range regex (RFC-1918, 169.254.x, CGNat)
   ISO whitelist for currency and country codes
```

### Security Controls Reference

| Control | Implementation | Threat Mitigated |
|---|---|---|
| API key auth | `ApiKeyAuthFilter` + constant-time XOR | Unauthorized access |
| IP whitelist | Per-key prefix match in `ApiKeyAuthFilter` | Leaked key reuse |
| SSRF guard | `SsrfGuard` (scheme + hostname + IP regex) | Internal recon via webhook |
| Idempotency | Redis SET NX + DB unique constraint | Double-charge / duplicate TX |
| Optimistic locking | JPA `@Version` on `TransactionEntity` | Concurrent status overwrite |
| Timing attack | Constant-time XOR comparison | Key length oracle |
| Info leakage | Same 401 for wrong key AND wrong IP | Key validity oracle |
| Payload DoS | `server.tomcat.max-http-form-post-size: 64KB` | Oversized JSON attack |
| Stack trace leakage | `server.error.include-stacktrace: never` | Internal topology exposure |
| Security headers | HSTS, X-Frame-Options DENY, Referrer-Policy | Clickjacking, MITM |
| PII in logs | Logs only metadata (no bodies per PCI) | Regulatory compliance |

---

## Tech Stack

| Category | Technology | Version | Purpose |
|---|---|---|---|
| Language | Java | 17 LTS | Core language |
| Framework | Spring Boot | 3.2.5 | Application framework |
| Persistence | Spring Data JPA / Hibernate | 6.x | ORM layer |
| Database | MySQL | 8.0 | Primary data store |
| Cache / Idempotency | Redis | 7.x | Distributed idempotency lock, 24h TTL |
| Migrations | Flyway | 9.x | Schema versioning (V1–V3) |
| Mapping | MapStruct | 1.5.5.Final | DTO/Entity/Domain conversion |
| Boilerplate | Lombok | 1.18.32 | Code generation |
| Validation | Jakarta Bean Validation | 3.x | Request structural validation |
| API Docs | SpringDoc OpenAPI | 2.5.0 | Swagger UI generation |
| Resilience | Resilience4j | 2.2.0 | CircuitBreaker + Retry |
| Logging | Logstash Logback Encoder | 7.4 | Structured JSON logs (prod profile) |
| Security | Spring Security | 6.x | Filter chain, stateless, HSTS |
| Testing | JUnit 5 + Mockito | 5.x | Unit testing |
| CI/CD | GitHub Actions | — | Automated pipelines |
| Containers | Docker + Docker Compose | — | Containerization |

---

## Hexagonal Architecture

This project strictly follows **Ports and Adapters (Hexagonal) Architecture** as defined by Alistair Cockburn.

### Core Principle

The **domain and application core** has zero dependencies on infrastructure or frameworks. All dependencies point **inward** toward the domain.

```
                    ┌─────────────────────────────────┐
                    │         INFRASTRUCTURE           │
                    │  ┌───────────────────────────┐  │
                    │  │       APPLICATION         │  │
                    │  │  ┌─────────────────────┐  │  │
                    │  │  │      DOMAIN         │  │  │
                    │  │  │  Models, Exceptions  │  │  │
                    │  │  │  Business Rules      │  │  │
                    │  │  └─────────────────────┘  │  │
                    │  │  Use Cases / Services      │  │
                    │  │  Ports (IN/OUT interfaces) │  │
                    │  └───────────────────────────┘  │
                    │  Adapters (HTTP, JPA, Providers) │
                    │  Security, Config, Observability │
                    └─────────────────────────────────┘

    Dependencies: Infrastructure → Application → Domain
    (Never the reverse)
```

### Layer Descriptions

**Domain Layer** (`domain/`)
- Pure Java business models: `Transaction`, `Customer`, `TransactionStatus`, `DocumentType`
- Business exception hierarchy: `BusinessException` → concrete exceptions with error codes
- Port interfaces: `CreateTransactionUseCase`, `GetTransactionUseCase`, `TransactionRepositoryPort`, `PaymentProviderPort`
- **Zero framework dependencies** — only Java SE

**Application Layer** (`application/`)
- Use case implementations: `CreateTransactionService` (8-step orchestration), `GetTransactionService`
- Domain validators: `TransactionDomainValidator` (business rules + SSRF + amount limits)
- Depends only on the domain layer

**Infrastructure Layer** (`infrastructure/`)
- **Security**: `SsrfGuard`
- **Inbound adapters**: HTTP REST controller, DTOs, mappers, exception handler, security filters
- **Outbound adapters**: JPA persistence adapter, Redis idempotency service, provider mock adapter
- **Configuration**: OpenAPI, Security, Resilience4j, Logging

---

## Package Structure

```
co.tumipay.orchestrator/
│
├── TransactionOrchestratorApplication.java
│
├── domain/                                          # DOMAIN LAYER (pure Java, no frameworks)
│   ├── model/
│   │   ├── Transaction.java                         # Aggregate Root (@Builder, @With, immutable)
│   │   ├── Customer.java                            # Value Object (@Builder, @With)
│   │   ├── TransactionStatus.java                   # Enum with isFinal()
│   │   └── DocumentType.java                        # Enum with fromCode()
│   ├── exception/
│   │   ├── BusinessException.java                   # Abstract base (errorCode field)
│   │   ├── TransactionNotFoundException.java        # Code 007
│   │   ├── DuplicateTransactionException.java       # Code 011
│   │   ├── ValidationException.java                 # Code 001 (List<String> violations)
│   │   ├── PaymentProviderException.java            # Code 010
│   │   └── PaymentMethodNotFoundException.java      # Code 008
│   └── port/
│       ├── in/
│       │   ├── CreateTransactionUseCase.java        # Primary port (inbound)
│       │   └── GetTransactionUseCase.java           # Primary port (inbound)
│       └── out/
│           ├── TransactionRepositoryPort.java       # Secondary port (outbound)
│           └── PaymentProviderPort.java             # Secondary port (outbound)
│
├── application/                                     # APPLICATION LAYER
│   ├── service/
│   │   ├── CreateTransactionService.java            # 8-step orchestration with resilience
│   │   └── GetTransactionService.java               # @Transactional(readOnly=true)
│   └── validator/
│       └── TransactionDomainValidator.java          # Business rules + SSRF + amount bounds
│
└── infrastructure/                                  # INFRASTRUCTURE LAYER
    ├── security/                                    # Security components
    │   └── SsrfGuard.java                           # Webhook/redirect URL safety validation
    │
    ├── inbound/
    │   └── http/
    │       ├── controller/
    │       │   └── TransactionController.java        # REST adapter (thin, delegates to use cases)
    │       ├── dto/
    │       │   ├── request/
    │       │   │   ├── CreateTransactionRequest.java # snake_case, Bean Validation annotations
    │       │   │   └── CustomerRequest.java
    │       │   └── response/
    │       │       ├── ApiResponse.java              # Generic envelope {code, message, data}
    │       │       └── TransactionResponse.java
    │       ├── mapper/
    │       │   └── TransactionHttpMapper.java        # MapStruct HTTP mapper
    │       ├── exception/
    │       │   ├── ErrorCode.java                    # Enum catalogue (000–099)
    │       │   └── GlobalExceptionHandler.java       # @RestControllerAdvice
    │       └── filter/                              # Security filter chain
    │           ├── CorrelationIdFilter.java          # Order 1 — correlation ID + client IP → MDC
    │           └── ApiKeyAuthFilter.java             # Auth + constant-time validation + IP whitelist
    │
    ├── outbound/
    │   ├── persistence/
    │   │   ├── entity/
    │   │   │   ├── TransactionEntity.java            # @Version for optimistic locking
    │   │   │   └── CustomerEntity.java
    │   │   ├── repository/
    │   │   │   └── TransactionJpaRepository.java    # Spring Data JPA
    │   │   ├── mapper/
    │   │   │   └── TransactionPersistenceMapper.java # MapStruct persistence mapper
    │   │   └── adapter/
    │   │       └── TransactionRepositoryAdapter.java # Implements TransactionRepositoryPort
    │   ├── cache/
    │   │   └── IdempotencyService.java              # Redis SET NX atomic lock (24h TTL)
    │   └── provider/
    │       └── mock/
    │           └── MockPaymentProviderAdapter.java   # @Retry + @CircuitBreaker + fallbacks
    │
    └── config/
        ├── SecurityConfig.java                      # Spring Security + HSTS + headers
        ├── ResilienceConfig.java                    # Programmatic Resilience4j beans
        └── OpenApiConfig.java                       # SpringDoc configuration
```

---

## Design Patterns Applied

### 1. Hexagonal Architecture (Ports & Adapters)
Foundational pattern. Domain has zero infrastructure dependencies; ports define contracts; adapters implement them.

### 2. Strategy Pattern — Provider Selection
All `PaymentProviderPort` implementations register as Spring beans. `CreateTransactionService` iterates them and calls `supports(paymentMethodId)` at runtime.

```java
// Adding a new provider = zero changes to existing code (Open/Closed Principle)
@Component
public class PseRealAdapter implements PaymentProviderPort {
    @Override public boolean supports(String method) { return "PSE".equals(method); }
    @Override public Transaction process(Transaction tx) { /* real PSE HTTP call */ }
}
```

### 3. Factory Method — `Transaction.createNew(...)`
Encapsulates construction: assigns UUID, sets PENDING status, records `processedAt` timestamp.

### 4. Immutable Domain Model with `@With`
`Transaction` and `Customer` are immutable. State transitions produce new instances via Lombok `@With`, preventing accidental mutation across threads.

```java
Transaction updated = saved.withStatus(TransactionStatus.PROCESSING);
```

### 5. Repository Pattern — `TransactionRepositoryPort`
Domain never references JPA, MySQL, or Hibernate. Persistence is fully swappable behind the port.

### 6. Chain of Responsibility — `TransactionDomainValidator`
Runs all validation rules sequentially, collecting **all violations** before throwing `ValidationException`. Clients receive complete feedback in a single round-trip.

### 7. Circuit Breaker + Retry Composition
Resilience4j annotations on `MockPaymentProviderAdapter`:
- **CircuitBreaker** (50% failure rate, 30 s open state) trips on sustained errors
- **Retry** (3 attempts, exponential backoff 1 s → 2 s → 4 s) handles transient failures
- Fallback methods propagate `PaymentProviderException` when all retries exhaust

### 8. Optimistic Locking — `@Version` on `TransactionEntity`
Prevents race conditions when two threads attempt to update the same transaction status concurrently. JPA throws `OptimisticLockException` on stale reads.

### 9. Template Method — Exception Hierarchy
`BusinessException` provides the error-code template; concrete subclasses supply specific codes and messages.

---

## API Contract

### Authentication (Required)

All endpoints (except actuator health and Swagger) require the `X-API-Key` header:

```
X-API-Key: <your-api-key>
```

Missing or invalid keys return HTTP **401**.

### Tracing Header (Optional)

```
X-Correlation-ID: <uuid>
```

If not provided, the server generates one and returns it in every response.

### Base URL
```
http://localhost:8080/api/v1
```

---

### POST /api/v1/transactions

Creates and processes a new payment transaction.

**Request Headers**

| Header | Required | Description |
|---|---|---|
| `Content-Type` | YES | Must be `application/json` |
| `X-API-Key` | YES | Authenticated API key |
| `X-Correlation-ID` | NO | Client-supplied trace ID (UUID recommended) |

**Request Body**
```json
{
  "client_transaction_id": "ORDER-2024-001",
  "amount": 150000,
  "currency_code": "COP",
  "country_code": "CO",
  "payment_method_id": "PSE",
  "webhook_url": "https://api.yourcompany.com/webhooks/payments",
  "redirect_url": "https://yourcompany.com/payment/result",
  "description": "Purchase order #ORDER-2024-001",
  "expiration_time": "2024-12-31T23:59:59Z",
  "customer": {
    "document_type": "CC",
    "document_number": "1234567890",
    "country_calling_code": "+57",
    "phone_number": "3001234567",
    "email": "customer@example.com",
    "first_name": "Juan",
    "middle_name": "Carlos",
    "last_name": "Pérez",
    "second_last_name": "García"
  }
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `client_transaction_id` | String (max 100) | YES | Idempotency key; must be unique per merchant |
| `amount` | Long | YES | Cents; > 0 and ≤ `MAX_AMOUNT_CENTS` |
| `currency_code` | String (3) | YES | ISO 4217: `COP`, `USD`, `EUR`, `MXN`, `BRL`… |
| `country_code` | String (2) | YES | ISO 3166-1 Alpha-2: `CO`, `US`, `BR`… |
| `payment_method_id` | String (max 50) | YES | `PSE`, `CARD_VISA`, `CARD_MC`, `NEQUI`, `DAVIPLATA`, `EFECTY`, `BRE_B` |
| `webhook_url` | String (max 2048) | YES | HTTPS public URL; private IPs rejected (SSRF) |
| `redirect_url` | String (max 2048) | YES | Public URL; private IPs rejected (SSRF) |
| `description` | String (max 500) | NO | Human-readable description |
| `expiration_time` | ISO 8601 | NO | Must be future datetime |
| `customer.first_name` | String (max 100) | YES | |
| `customer.last_name` | String (max 100) | YES | |
| `customer.document_type` | String | NO | `CC`, `CE`, `NIT`, `PP`, `TI`, `RUT`, `DNI`, `NUIP` |
| `customer.document_number` | String | NO | |
| `customer.country_calling_code` | String | NO | E.g., `+57` |
| `customer.phone_number` | String | NO | Digits only, 7–15 characters |
| `customer.email` | String (max 254) | NO | Valid format |

**Success Response — HTTP 201 Created**
```json
{
  "code": "000",
  "message": "Successful operation",
  "data": {
    "transaction_id": "550e8400-e29b-41d4-a716-446655440000",
    "processed_at": "2024-11-15T10:30:00Z",
    "client_transaction_id": "ORDER-2024-001",
    "payment_method_id": "PSE",
    "currency_code": "COP",
    "country_code": "CO",
    "description": "Purchase order #ORDER-2024-001",
    "status": "PROCESSING"
  }
}
```

**Idempotency**: Submitting the same `client_transaction_id` twice returns the **original transaction** (HTTP 200) if already processed, or HTTP 409 if processing is still in-flight.

---

### GET /api/v1/transactions/{transaction_id}

Retrieves a transaction by orchestrator-assigned UUID.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `transaction_id` | UUID | Orchestrator-assigned UUID from the POST response |

**Success Response — HTTP 200 OK**
```json
{
  "code": "000",
  "message": "Successful operation",
  "data": {
    "transaction_id": "550e8400-e29b-41d4-a716-446655440000",
    "processed_at": "2024-11-15T10:30:00Z",
    "client_transaction_id": "ORDER-2024-001",
    "payment_method_id": "PSE",
    "currency_code": "COP",
    "country_code": "CO",
    "description": "Purchase order #ORDER-2024-001",
    "status": "PROCESSING"
  }
}
```

---

### Response Envelope

Every response follows the standard envelope:

```json
{
  "code": "string",    // API result code (see Error Codes Dictionary)
  "message": "string", // Human-readable description
  "data": {}           // Payload on success; violation list or null on error
}
```

---

## Error Codes Dictionary

| Code | HTTP Status | Name | Description |
|---|---|---|---|
| `000` | 2xx | Success | Operation completed successfully |
| `001` | 422 | Validation Error | Required field missing or format invalid |
| `002` | 422 | Invalid Email | Email format validation failed |
| `003` | 422 | Invalid Currency | Currency code not in ISO 4217 whitelist |
| `004` | 422 | Invalid Country | Country code not in ISO 3166-1 Alpha-2 whitelist |
| `005` | 422 | Invalid Amount | Zero, negative, or exceeds maximum allowed |
| `006` | 422 | Invalid URL | Malformed URL or points to private/internal address (SSRF) |
| `007` | 404 | Transaction Not Found | No transaction with given ID exists |
| `008` | 400 | Payment Method Not Supported | No provider registered for the given payment method |
| `009` | 422 | Invalid Document Type | Document type code not in allowed list |
| `010` | 502 | Provider Error | Payment provider returned error or is unreachable |
| `011` | 409 | Duplicate Transaction | `client_transaction_id` already exists |
| `012` | 422 | Invalid Expiration Time | Expiration time is past or malformed |
| `401` | 401 | Unauthorized | Missing or invalid API key |
| `429` | 429 | Too Many Requests | Rate limit exceeded |
| `099` | 500 | Internal Server Error | Unexpected server-side error |

---

## Database Schema

### Flyway Migration Versions

| Version | File | Description |
|---|---|---|
| V1 | `V1__create_initial_schema.sql` | All tables: transactions, customers, references |
| V2 | `V2__insert_reference_data.sql` | Seed data: statuses, document types, payment methods |
| V3 | `V3__add_version_column.sql` | `version BIGINT` on transactions (optimistic locking) |

### Entity Relationship Diagram (ERD)

```
┌──────────────────────────────────────┐
│          document_types              │
├──────────────────────────────────────┤
│ PK code        VARCHAR(10)  NOT NULL │
│    name        VARCHAR(100) NOT NULL │
│    description VARCHAR(255)          │
│    country     CHAR(2)      NOT NULL │
│    active      TINYINT(1)   DEF 1   │
│    created_at  DATETIME(6)           │
└──────────────────────────┬───────────┘
                           │ FK
                           │
┌──────────────────────────▼─────────────────────────┐
│                      customers                      │
├─────────────────────────────────────────────────────┤
│ PK id                  CHAR(36)   NOT NULL (UUID v4)│
│ FK document_type_code  VARCHAR(10)                  │
│    document_number     VARCHAR(50)                  │
│    country_calling_code VARCHAR(10)                 │
│    phone_number        VARCHAR(20)                  │
│    email               VARCHAR(254)  IDX            │
│    first_name          VARCHAR(100) NOT NULL        │
│    middle_name         VARCHAR(100)                 │
│    last_name           VARCHAR(100) NOT NULL        │
│    second_last_name    VARCHAR(100)                 │
│    created_at          DATETIME(6)  NOT NULL        │
└───────────────────────────────────┬─────────────────┘
                                    │ FK
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                            transactions                                 │
├────────────────────────────────────────────────────────────────────────┤
│ PK id                    CHAR(36)   NOT NULL (UUID v4)                 │
│ UQ client_transaction_id VARCHAR(100) NOT NULL                         │
│    amount                BIGINT     NOT NULL CHECK (amount > 0)        │
│    currency_code         CHAR(3)    NOT NULL  IDX                      │
│    country_code          CHAR(2)    NOT NULL  IDX                      │
│ FK payment_method_id     VARCHAR(50) NOT NULL                          │
│    webhook_url           VARCHAR(2048) NOT NULL                        │
│    redirect_url          VARCHAR(2048) NOT NULL                        │
│    description           VARCHAR(500)                                  │
│    expiration_time       DATETIME(6)                                   │
│ FK status_code           VARCHAR(20) NOT NULL DEF 'PENDING'  IDX      │
│ FK customer_id           CHAR(36)   NOT NULL                           │
│    version               BIGINT     NOT NULL DEF 0  ← optimistic lock │
│    processed_at          DATETIME(6) NOT NULL                          │
│    created_at            DATETIME(6) NOT NULL DEF CURRENT  IDX        │
│    updated_at            DATETIME(6)                                   │
└──────────────────┬─────────────────────────────┬───────────────────────┘
                   │ FK                           │ FK
                   │                             │
┌──────────────────▼──────────┐  ┌──────────────▼─────────────────────┐
│      payment_methods        │  │     transaction_statuses            │
├─────────────────────────────┤  ├─────────────────────────────────────┤
│ PK id       VARCHAR(50)     │  │ PK code    VARCHAR(20) NOT NULL     │
│    name     VARCHAR(100)    │  │    name    VARCHAR(100) NOT NULL    │
│    provider VARCHAR(100)    │  │    is_final TINYINT(1) DEF 0       │
│    active   TINYINT(1)      │  └─────────────────────────────────────┘
│    created_at DATETIME      │
└─────────────────────────────┘
          │ REF
          │
┌─────────▼────────────────────────────────────────┐
│        transaction_provider_responses             │
├──────────────────────────────────────────────────┤
│ PK id               CHAR(36)   NOT NULL (UUID v4)│
│ FK transaction_id   CHAR(36)   NOT NULL   IDX    │
│    provider         VARCHAR(100) NOT NULL        │
│    request_payload  TEXT        (sanitized)      │
│    response_code    VARCHAR(20)                  │
│    response_message VARCHAR(500)                 │
│    http_status      INT                          │
│    responded_at     DATETIME(6) NOT NULL         │
└──────────────────────────────────────────────────┘
```

### Transaction Lifecycle States

```
                         ┌─────────┐
              ┌──────────│ PENDING │──────────┐
              │          └─────────┘          │
              │               │               │
              ▼               ▼               ▼
         ┌──────────┐  ┌────────────┐  ┌──────────┐
         │ CANCELLED│  │ PROCESSING │  │  ERROR   │ ◄── Terminal
         └──────────┘  └────────────┘  └──────────┘
                              │
                       ┌──────┴──────┐
                       │             │
                  ┌────▼───┐   ┌─────▼────┐
                  │APPROVED│   │ REJECTED │ ◄── Terminal
                  └────────┘   └──────────┘
                  ▲
             ┌────┴───┐
             │ EXPIRED│ ◄── Terminal
             └────────┘
```

### Reference Data

**Payment Methods**

| ID | Name | Provider |
|---|---|---|
| `PSE` | PSE - Débito en línea | MOCK |
| `CARD_VISA` | Tarjeta de Crédito Visa | MOCK |
| `CARD_MC` | Tarjeta de Crédito Mastercard | MOCK |
| `NEQUI` | Nequi - Billetera Digital | MOCK |
| `DAVIPLATA` | Daviplata - Billetera Digital | MOCK |
| `EFECTY` | Efecty - Pago en efectivo | MOCK |
| `BRE_B` | Bre-B - Pagos Inmediatos | MOCK |

**Document Types**

| Code | Name | Country |
|---|---|---|
| `CC` | Cédula de Ciudadanía | CO |
| `CE` | Cédula de Extranjería | CO |
| `NIT` | Número de Identificación Tributaria | CO |
| `PP` | Pasaporte | CO |
| `TI` | Tarjeta de Identidad | CO |
| `RUT` | Registro Único Tributario | CO |
| `DNI` | Documento Nacional de Identidad | CO |
| `NUIP` | Número Único de Identificación Personal | CO |

---

## Observability Stack

### Services

| Service | URL | Purpose |
|---|---|---|
| Application | `http://localhost:8080` | Main API |
| Swagger UI | `http://localhost:8080/swagger-ui.html` | API documentation |
| Actuator Health | `http://localhost:8080/actuator/health` | DB + Redis health probes |

### Structured Logging

Logs follow profile-based configuration (`logback-spring.xml`):

| Profile | Format | Destination |
|---|---|---|
| `default`, `local`, `test` | Colored console (human-readable) | stdout |
| `prod`, `docker`, `staging` | JSON (Logstash Logback Encoder) | stdout → log aggregator |

Every log line includes MDC fields: `correlation_id`, `client_ip`, `client_transaction_id` (during processing).

### Health

`GET /actuator/health` returns composite health (auto-configured by Spring Boot Actuator):
- **DB**: HikariCP connection pool status
- **Redis**: PING command

---

## CI/CD Pipeline

The GitHub Actions pipeline (`.github/workflows/ci.yml`) runs automatically on every push and pull request.

### Pipeline Jobs

```
Push / PR
    │
    ▼
┌─────────────────────────────────┐
│  Job 1: Build & Test            │
│  - Checkout                     │
│  - Setup JDK 17 (Temurin)      │
│  - mvn clean verify             │
│  - JaCoCo coverage report       │
│  - Upload surefire reports      │
└──────────┬──────────────────────┘
           │ On success
     ┌─────┴──────┐
     │            │
     ▼            ▼
┌──────────┐  ┌──────────────────────────┐
│  Job 2:  │  │  Job 3: Docker Build     │
│  Sonar   │  │  (main/develop only)     │
│  Analysis│  │  - mvn package -DskipTests│
│  (code   │  │  - Docker Buildx         │
│  quality)│  │  - Login GHCR            │
│          │  │  - Build & push image    │
└──────────┘  └──────────────────────────┘
```

### Trigger Rules

| Branch Pattern | Build & Test | Code Quality | Docker Push |
|---|---|---|---|
| `feature/**` | YES | NO | NO |
| `develop` | YES | YES | YES |
| `main` | YES | YES | YES |
| PR to main/develop | YES | NO | NO |

### Docker Image

Multi-stage build with security hardening:
- **Stage 1**: Dependency cache layer (Maven)
- **Stage 2**: Build layer (compiles JAR)
- **Stage 3**: Runtime — `eclipse-temurin:17-jre-alpine`, non-root user `tumipay` (UID 1001)

JVM tuning flags in `Dockerfile`:
```
-server -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=16m
-XX:+UseStringDeduplication -XX:+HeapDumpOnOutOfMemoryError
-Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8 -Duser.timezone=UTC
```

Images published to GitHub Container Registry (`ghcr.io`) with tags: `latest`, `main`/`develop`, `<branch>-<sha>`.

---

## Code Quality Strategy

### Three-Layer Validation

```
Request
  │
  ├─ Layer 1: Bean Validation (Jakarta)
  │    @NotBlank, @NotNull, @Positive, @Pattern, @Size, @Email
  │    Enforces: structural constraints, field presence, format
  │    Throws: MethodArgumentNotValidException → HTTP 422
  │
  ├─ Layer 2: Domain Validation (TransactionDomainValidator)
  │    Enforces: amount bounds, ISO codes, document types
  │    Collects ALL violations before throwing
  │    Throws: ValidationException → HTTP 422
  │
  └─ Layer 3: SSRF Guard (SsrfGuard)
       Enforces: webhook/redirect URLs are publicly routable
       Blocks: private IPs, cloud metadata, dangerous schemes
       Part of Layer 2 — runs after URL format passes
```

### Static Analysis (SonarCloud)

Required secrets:
- `SONAR_TOKEN`: SonarCloud project token
- `GITHUB_TOKEN`: Provided by GitHub Actions automatically

Metrics tracked: coverage, code smells, technical debt, security hotspots, duplications.

JaCoCo threshold: **70% line coverage** minimum (build fails below threshold).

### Testing Strategy

| Test Type | Tool | Coverage Target |
|---|---|---|
| Unit: use cases | JUnit 5 + Mockito | `CreateTransactionService`, `GetTransactionService` |
| Unit: validators | JUnit 5 | `TransactionDomainValidator`, `SsrfGuard` |
| Unit: filters | JUnit 5 + MockMvc | `ApiKeyAuthFilter`, `CorrelationIdFilter` |
| Integration (future) | Spring Boot Test + Testcontainers + MySQL | Persistence adapters, full flow |

---

## How to Run

### Prerequisites

- Java 17+
- Maven 3.9+
- Docker & Docker Compose

### Option 1: Full Stack (Docker Compose)

```bash
# Start all services: MySQL, Redis, App
docker-compose up --build -d

# Follow application logs
docker-compose logs -f orchestrator

# Stop
docker-compose down

# Stop and remove volumes (clean state)
docker-compose down -v
```

Services started:
- Application: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

### Option 2: Local Development (MySQL + Redis via Docker)

```bash
# Start infrastructure only
docker-compose up mysql redis -d

# Run application locally
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Option 3: Tests Only

```bash
# Unit tests (H2 in-memory, no external services needed)
mvn test

# Full build + test + coverage report
mvn clean verify
```

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/tumipay_orchestrator?...` | MySQL JDBC URL |
| `DB_USERNAME` | `tumipay` | Database username |
| `DB_PASSWORD` | `tumipay123` | Database password (**change in production**) |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis password (**set in production**) |
| `API_KEYS` | `test-key-dev-001,test-key-dev-002` | Comma-separated API keys (**use secrets manager in production**) |
| `SERVER_PORT` | `8080` | HTTP server port |
| `LOG_LEVEL` | `INFO` | Log level for `co.tumipay` packages |
| `MAX_AMOUNT_CENTS` | `10000000000` | Maximum transaction amount in cents |

### API Quick Test (cURL)

```bash
# Create a transaction
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key-dev-001" \
  -H "X-Correlation-ID: $(uuidgen)" \
  -d '{
    "client_transaction_id": "ORDER-TEST-001",
    "amount": 150000,
    "currency_code": "COP",
    "country_code": "CO",
    "payment_method_id": "PSE",
    "webhook_url": "https://webhook.site/your-unique-id",
    "redirect_url": "https://example.com/result",
    "description": "Test payment",
    "customer": {
      "first_name": "Juan",
      "last_name": "Pérez",
      "email": "juan@example.com",
      "document_type": "CC",
      "document_number": "1234567890"
    }
  }'

# Query a transaction
curl -H "X-API-Key: test-key-dev-001" \
  http://localhost:8080/api/v1/transactions/550e8400-e29b-41d4-a716-446655440000

# Health probe (no auth required)
curl http://localhost:8080/actuator/health

```

### SSRF Guard — Rejected Examples

```bash
# These webhook_url values will be rejected with HTTP 422

# Private IP (RFC-1918)
"webhook_url": "http://10.0.0.1/callback"

# AWS metadata service
"webhook_url": "http://169.254.169.254/latest/meta-data"

# Localhost
"webhook_url": "http://localhost:8080/internal/admin"

# Non-HTTP scheme
"webhook_url": "file:///etc/passwd"
```

---

## Architectural Decisions

### ADR-001: Hexagonal Architecture
**Decision**: Ports & Adapters as foundational pattern.  
**Rationale**: Payment systems require frequent provider changes, deep testability without infrastructure, and clean separation of concerns.  
**Consequence**: More boilerplate (interfaces, adapters, mappers) but dramatically improved maintainability and testability.

### ADR-002: Amount in Cents (Long)
**Decision**: Store and transmit monetary amounts as `Long` integers representing the smallest currency unit.  
**Rationale**: Eliminates floating-point precision issues. Industry standard (Stripe, Adyen, PayU).  
**Consequence**: Clients must convert — $1,500 COP = `150000`.

### ADR-003: UUID v4 for Transaction IDs
**Decision**: UUID v4 for all internal transaction identifiers.  
**Rationale**: Globally unique, no sequential enumeration risk, compatible with distributed systems.  
**Consequence**: Stored as `CHAR(36)` in MySQL. Slightly larger than auto-increment.

### ADR-004: Persist-Before-Send
**Decision**: Always persist the transaction in PENDING status **before** calling the payment provider.  
**Rationale**: Guarantees audit trail even if the provider call fails. Prevents ghost transactions.  
**Consequence**: Two `save()` calls per transaction; both are within a single `@Transactional` boundary.

### ADR-005: Layered Idempotency
**Decision**: Three independent idempotency layers: Redis SET NX (atomic, distributed) → DB unique constraint (fallback) → `@Version` optimistic locking (concurrent updates).  
**Rationale**: Single-layer idempotency has race condition windows. Defense-in-depth eliminates them.  
**Consequence**: Redis dependency added. Graceful degradation: if Redis is down, DB constraint still prevents duplicates.

### ADR-006: Strategy Pattern for Providers
**Decision**: All provider adapters implement `PaymentProviderPort` and are auto-discovered via Spring's `List<PaymentProviderPort>` injection.  
**Rationale**: Open/Closed Principle — adding a provider = new class only, zero existing code changes.  
**Consequence**: O(n) linear scan at routing time. For large provider counts, a Map-based registry would be more efficient.

### ADR-007: Constant-Time API Key Comparison
**Decision**: XOR-based string comparison instead of `.equals()` for API key validation.  
**Rationale**: Early-exit `.equals()` leaks timing information proportional to how many characters match, enabling brute-force oracle attacks.  
**Consequence**: Marginal CPU overhead (microseconds) for a critical security guarantee.

### ADR-008: Correlation ID en MDC
**Decision**: `CorrelationIdFilter` genera o reusa el `X-Correlation-ID` y lo publica en SLF4J MDC para que todos los logs del request lleven el mismo ID de traza.  
**Rationale**: Sin un correlation ID compartido, correlacionar logs de una misma request es imposible en ambientes concurrentes.  
**Consequence**: MDC se limpia en `finally` para evitar fugas entre requests en el thread pool.

### ADR-009: SSRF Prevention in Validator
**Decision**: `SsrfGuard` validates `webhook_url` and `redirect_url` at request time, blocking private IPs, loopback, cloud metadata endpoints, and non-HTTP schemes.  
**Rationale**: Without this check, the gateway can be weaponized as a proxy to scan or call internal services.  
**Consequence**: DNS rebinding attacks are NOT mitigated here (require runtime resolution in the HTTP client). Documented as a known limitation.

### ADR-010: Structured JSON Logging
**Decision**: Profile-based logging — colored console in `local`/`test`, Logstash JSON in `prod`/`docker`.  
**Rationale**: JSON logs are required for ELK/Splunk ingestion in production. Human-readable format improves developer experience locally.  
**Consequence**: `logback-spring.xml` must be maintained as profiles evolve.

### ADR-011: Non-Root Docker User
**Decision**: All container processes run as UID 1001 (`tumipay` user), not as root.  
**Rationale**: Container escape vulnerabilities are significantly more impactful if the process runs as root inside the container.  
**Consequence**: Heap dump path and other writable directories must be pre-created and owned by UID 1001 in the Dockerfile.

---

## Assumptions

1. **Webhook-based final status**: Providers notify APPROVED/REJECTED asynchronously via webhook. The orchestrator sets PROCESSING after routing. A webhook receiver endpoint is not included in this version (see R-003).

2. **API key management**: API keys are loaded from environment variables or a secrets manager. Rotation requires redeployment in this implementation; a future version should support dynamic key rotation via a key management service.

3. **Single-region deployment**: No multi-region failover. Redis and MySQL are single-instance; replication is an infrastructure concern outside this microservice.

4. **Amount precision**: All monetary amounts are in the smallest currency unit (cents). Clients are responsible for conversion.

5. **Single currency per transaction**: Multi-currency conversion is out of scope.

6. **Idempotency TTL**: Redis idempotency keys expire after 24 hours. Clients should not retry requests with the same `client_transaction_id` after 24 hours.

7. **Brute-force protection**: Not yet implemented. Authentication failures are logged but no automatic IP lockout is in place. This is tracked as a pending next step (see [Next Steps — Fase 1](#fase-1--mvp--producción-sprint-1-3)).

8. **UTF-8 encoding**: All string data stored as `utf8mb4` in MySQL, supporting full Unicode.

---

## Identified Risks & Mitigation Plan

This section documents all identified risks across five categories: Técnicos, Seguridad, Cumplimiento, Operacionales y Terceros. Cada riesgo incluye probabilidad, impacto, severidad, indicadores de detección y acciones de mitigación concretas.

### Matriz de Riesgos — Resumen Ejecutivo

```
IMPACTO
  │
  │  ALTO  │ R-S02  │ R-T03  R-T07 │ R-T06  R-B01  R-B02 │
  │        │ R-O03  │ R-S01  R-P01 │ R-B03  R-S03        │
  │        │        │              │                      │
  │ MEDIO  │ R-T02  │ R-T05        │                      │
  │        │ R-T01  │ R-S04  R-O04 │                      │
  │        │ R-P02  │              │                      │
  │        │        │              │                      │
  │  BAJO  │ R-O01  │ R-O02        │                      │
  │        │        │              │                      │
  └────────┴────────┴──────────────┴──────────────────────▶ PROBABILIDAD
               BAJA        MEDIA              ALTA
```

| Severidad | Color | Criterio |
|---|---|---|
| 🔴 **Crítica** | Rojo | Alta probabilidad × Alto impacto — requiere acción inmediata |
| 🟠 **Alta** | Naranja | Media probabilidad × Alto impacto, o Alta × Medio |
| 🟡 **Media** | Amarillo | Media probabilidad × Medio impacto |
| 🟢 **Baja** | Verde | Baja probabilidad o bajo impacto |

---

### Tabla Consolidada

| ID | Riesgo | Categoría | Prob. | Impacto | Severidad | Estado |
|---|---|---|---|---|---|---|
| R-T03 | Receptor de webhooks ausente | Técnico | ALTA | ALTO | 🔴 Crítica | Pendiente |
| R-B01 | Incumplimiento regulatorio SFC / Habeas Data | Cumplimiento | ALTA | ALTO | 🔴 Crítica | Parcial |
| R-B02 | Fraude / Lavado de activos | Cumplimiento | ALTA | ALTO | 🔴 Crítica | Sin mitigar |
| R-B03 | Cobro doble al usuario final | Negocio | ALTA | ALTO | 🔴 Crítica | Parcial |
| R-T06 | DNS Rebinding en URLs de webhook | Seguridad | ALTA | ALTO | 🔴 Crítica | Documentado |
| R-S01 | Fuga / compromiso de API keys | Seguridad | ALTA | ALTO | 🔴 Crítica | Parcial |
| R-T07 | MySQL punto único de falla | Técnico | MEDIA | ALTO | 🟠 Alta | Sin mitigar |
| R-P01 | Cambios de API del proveedor sin aviso | Terceros | MEDIA | ALTO | 🟠 Alta | Sin mitigar |
| R-S03 | Amenaza interna (insider threat) | Seguridad | MEDIA | ALTO | 🟠 Alta | Sin mitigar |
| R-O03 | Caída total del proveedor de pago | Operacional | MEDIA | ALTO | 🟠 Alta | Sin mitigar |
| R-S02 | PII en logs | Seguridad | BAJA | ALTO | 🟠 Alta | Mitigado |
| R-T05 | Brute-force protection no implementada | Técnico | ALTA | MEDIO | 🟠 Alta | Pendiente |
| R-S04 | Compromiso de dependencias (supply chain) | Seguridad | MEDIA | MEDIO | 🟡 Media | Parcial |
| R-T02 | Divergencia de esquema test vs producción | Técnico | MEDIA | MEDIO | 🟡 Media | Pendiente |
| R-T01 | Acoplamiento con nuevo proveedor | Técnico | BAJA | MEDIO | 🟡 Media | Mitigado |
| R-O04 | Drift de configuración entre entornos | Operacional | MEDIA | MEDIO | 🟡 Media | Sin mitigar |
| R-P02 | Fallo de entrega de webhooks al merchant | Terceros | MEDIA | MEDIO | 🟡 Media | Sin mitigar |
| R-O02 | Error de configuración de monto máximo | Operacional | BAJA | MEDIO | 🟢 Baja | Mitigado |
| R-O01 | Sin plan de recuperación ante desastre | Operacional | BAJA | BAJO | 🟢 Baja | Sin mitigar |

---

## Riesgos Técnicos

### R-T01 — Acoplamiento con Nuevo Proveedor
**Probabilidad**: BAJA | **Impacto**: MEDIO | **Severidad**: 🟡 Media | **Estado**: ✅ Mitigado

**Descripción**: Agregar un nuevo proveedor de pago (Nequi, Bre-B, PSE) requiere implementar un adaptador completo con cliente HTTP, manejo de errores y lógica de reintento.

**Indicador de detección**: Tiempo de integración de nuevo proveedor > 2 semanas.

**Mitigación aplicada**:
- `PaymentProviderPort` define un contrato mínimo y estable (Hexagonal Architecture).
- Adaptadores se desarrollan de forma independiente sin modificar el core.
- `MockPaymentProvider` sirve como referencia de implementación.

**Acciones pendientes**:
1. Documentar la guía de implementación de adaptadores con checklist.
2. Crear un adaptador de prueba (`EchoPaymentProvider`) que refleje el request como respuesta, útil para testing de merchants en sandbox.

---

### R-T02 — Divergencia de Esquema Test vs Producción
**Probabilidad**: MEDIA | **Impacto**: MEDIO | **Severidad**: 🟡 Media | **Estado**: ⏳ Pendiente

**Descripción**: Los tests unitarios usan H2 in-memory con esquema generado por Hibernate (`ddl-auto: create`). MySQL 8 con Flyway puede tener comportamientos diferentes en collation, tipos de datos, y restricciones de clave foránea. Un bug puede pasar tests y fallar en producción.

**Indicador de detección**: Error de `FlywayMigrationException` o `DataIntegrityViolationException` en despliegue a staging que no ocurrió en tests.

**Acciones de mitigación**:
1. Agregar tests de integración con **Testcontainers** (`mysql:8.0`) que ejecuten las migraciones Flyway reales.
2. Separar tests unitarios (H2) de tests de integración (MySQL) con perfiles de Maven (`-P integration`).
3. Ejecutar los tests de integración en el pipeline de CI en la rama `develop`.
4. Añadir anotación `@FlywayTest` para rollback automático entre tests de integración.

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
class FlywayMigrationIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withUsername("tumipay").withPassword("tumipay123")
            .withDatabaseName("tumipay_orchestrator");

    @Test
    void allMigrationsShouldApplyCleanly() {
        // Si Spring arranca, las migraciones pasaron
    }
}
```

---

### R-T03 — Receptor de Webhooks del Proveedor Ausente
**Probabilidad**: ALTA | **Impacto**: ALTO | **Severidad**: 🔴 Crítica | **Estado**: ❌ Pendiente

**Descripción**: No existe endpoint para recibir notificaciones asíncronas del proveedor. Las transacciones quedan en estado `PROCESSING` indefinidamente. Sin resolución de estado, el merchant no puede confirmar el pago ni liberar el pedido.

**Indicador de detección**:
- Alerta: `COUNT(*) WHERE status = 'PROCESSING' AND created_at < NOW() - INTERVAL 10 MINUTE > 0`
- Dashboard: tasa de transacciones que nunca abandonan `PROCESSING`.

**Acciones de mitigación** (bloqueante para producción):
1. Implementar `POST /api/v1/webhooks/{provider}` con verificación de firma HMAC.
2. Implementar `UpdateTransactionStatusUseCase` con validación de transición de estado (`PROCESSING → APPROVED/REJECTED`).
3. Usar idempotencia en el receptor: ignorar `event_id` ya procesados (Redis SET NX).
4. Agregar `@Scheduled` job cada 5 minutos para marcar como `EXPIRED` las transacciones en `PROCESSING` pasado su `expiration_time`.
5. Emitir evento al merchant (webhook de salida) cuando el estado cambie.

---

### R-T05 — Brute-Force Protection No Implementada
**Probabilidad**: ALTA | **Impacto**: MEDIO | **Severidad**: 🟠 Alta | **Estado**: ❌ Pendiente

**Descripción**: No existe ningún mecanismo de bloqueo automático por IP ante intentos de autenticación repetidos. Un atacante puede probar combinaciones de API keys de forma ilimitada. Los intentos fallidos sólo quedan en logs.

**Indicador de detección**: Log pattern: `security.invalid_api_key` repetido con alta frecuencia desde la misma IP en un período corto.

**Acciones de mitigación** (ver Next Steps — Fase 1):
1. Implementar `BruteForceProtectionService` con contadores en Redis (`INCR` / `EXPIRE` atómicos).
2. Integrar el bloqueo en `ApiKeyAuthFilter` antes de la validación de clave.
3. Retornar HTTP 429 con header `Retry-After` cuando la IP esté bloqueada.
4. Configurar alerta en el agregador de logs cuando `security.invalid_api_key` > 10 eventos/min por IP.

---

### R-T06 — DNS Rebinding en URLs de Webhook
**Probabilidad**: ALTA | **Impacto**: ALTO | **Severidad**: 🔴 Crítica | **Estado**: ⚠️ Documentado

**Descripción**: `SsrfGuard` valida la URL en tiempo de request, pero no resuelve DNS. Un atacante registra `malicious.com → 1.2.3.4` (IP pública), pasa validación, luego cambia el DNS a `169.254.169.254` (AWS metadata). Cuando el HTTP client ejecuta el webhook, accede al endpoint interno.

**Indicador de detección**: Difícil de detectar sin controles de red. Señal: requests salientes inesperados hacia rangos internos capturados por firewall de egreso.

**Acciones de mitigación**:
1. En el HTTP client que ejecuta webhooks, **re-validar la IP resuelta** inmediatamente antes de abrir la conexión TCP:
   ```java
   .doOnConnected(conn -> {
       InetSocketAddress addr = (InetSocketAddress) conn.address();
       String resolvedIp = addr.getAddress().getHostAddress();
       if (ssrfGuard.isPrivateIp(resolvedIp)) {
           conn.dispose();
           throw new SsrfException("Resolved IP blocked: " + resolvedIp);
       }
   })
   ```
2. Implementar **reglas de egreso en firewall / Security Group** que bloqueen tráfico saliente hacia RFC-1918 y `169.254.0.0/16`.
3. Configurar **TTL mínimo de DNS** en el cliente (rechazar registros con TTL < 60s como indicador de rebinding).
4. Usar un **proxy de egreso dedicado** (ej: Squid) con allowlist de dominios autorizados para webhooks.

---

### R-T07 — MySQL Punto Único de Falla
**Probabilidad**: MEDIA | **Impacto**: ALTO | **Severidad**: 🟠 Alta | **Estado**: ❌ Sin mitigar

**Descripción**: Una sola instancia MySQL sin replicación ni failover automático. Una falla de hardware, corrupción de datos o mantenimiento no planeado detiene completamente el servicio.

**Indicador de detección**:
- Health: `GET /actuator/health` → `{"db": {"status": "DOWN"}}`
- Alerta: `hikaricp_connections_active / hikaricp_connections_max > 0.9` (pool exhausto)

**Acciones de mitigación**:
1. Configurar **AWS RDS Multi-AZ**: failover automático a réplica standby en < 60 segundos.
2. Habilitar **RDS automated backups** con retención de 7 días + point-in-time recovery.
3. Agregar **read replica** para consultas (`GetTransactionService`) y separar pool de escritura/lectura en HikariCP.
4. Monitorear conexiones activas de HikariCP: alerta cuando `hikaricp_connections_active / hikaricp_connections_max > 0.9`.
5. Documentar y probar el **runbook de failover manual** antes del go-live.

---

## Riesgos de Seguridad

### R-S01 — Fuga o Compromiso de API Keys
**Probabilidad**: ALTA | **Impacto**: ALTO | **Severidad**: 🔴 Crítica | **Estado**: ⚠️ Parcial

**Descripción**: Las API keys son la única barrera de autenticación. Si una key es filtrada (commits, logs, Slack, Postman collections compartidas), un atacante puede crear transacciones fraudulentas o consultar datos de clientes hasta que la key sea revocada.

**Indicador de detección**:
- Tráfico desde IPs desconocidas usando una key existente.
- `git log -S "test-key"` detecta keys en historial.
- Herramientas: **GitGuardian**, **TruffleHog**, **AWS Secrets Manager rotation alerts**.

**Acciones de mitigación**:
1. **Nunca** almacenar keys en código fuente. Usar `.gitignore` para archivos `.env`. Configurar **pre-commit hook** con TruffleHog.
2. Migrar a **AWS Secrets Manager** o **HashiCorp Vault** con rotación automática (90 días máximo).
3. Activar **IP whitelist por API key** (`tumipay.orchestrator.security.ip-whitelist`) en producción para todos los merchants.
4. Implementar **notificación automática** cuando una key se use desde una IP nueva (señal de alerta).
5. Definir **procedimiento de revocación de emergencia** (< 5 minutos) documentado en runbook.
6. Usar keys de al menos **32 bytes aleatorios** (256 bits de entropía), no strings predecibles.
7. Rotar keys de los entornos no-productivos (dev/staging) mensualmente.

---

### R-S02 — PII en Logs
**Probabilidad**: BAJA | **Impacto**: ALTO | **Severidad**: 🟠 Alta | **Estado**: ✅ Mitigado (verificar periódicamente)

**Descripción**: Datos personales del cliente (correo, número de documento, teléfono, nombre) podrían aparecer en logs si se agrega un `log.debug(transaction.toString())` o similar. Esto viola la Ley 1581/2012 (Habeas Data) y puede resultar en sanciones de la SIC.

**Indicador de detección**: Búsqueda periódica en logs: `grep -E "[0-9]{10}|@[a-z]+\.[a-z]+" /var/log/app/*.log`

**Mitigación aplicada**:
- `@ToString(exclude = {"email", "documentNumber", "phoneNumber"})` en `Customer`.
- Filters loguean solo metadata del request (método, URI, correlation ID) — nunca el body.
- Logback configurado para enmascarar patrones de email y CC con `MaskingPatternLayout`.

**Acciones pendientes**:
1. Ejecutar **auditoría trimestral de logs** para verificar que no aparecen campos PII.
2. Configurar **Data Masking en el agregador de logs** (ELK/Splunk) como segunda línea de defensa.
3. Definir **política de retención de logs**: máximo 90 días para logs de aplicación, 1 año para logs de auditoría (sin PII).
4. Agregar test automatizado que verifique que `Customer.toString()` no expone campos sensibles.

---

### R-S03 — Amenaza Interna (Insider Threat)
**Probabilidad**: MEDIA | **Impacto**: ALTO | **Severidad**: 🟠 Alta | **Estado**: ❌ Sin mitigar

**Descripción**: Un empleado con acceso a la BD de producción, a los secretos o al código puede exfiltrar datos de clientes, modificar transacciones o insertar código malicioso.

**Indicador de detección**: Accesos a BD fuera de horario laboral, queries de `SELECT *` masivos, modificaciones directas en tablas de producción.

**Acciones de mitigación**:
1. **Principio de mínimo privilegio**: acceso a BD de producción solo mediante herramientas auditadas (no cliente SQL directo); acceso requiere aprobación y tiene TTL.
2. Habilitar **MySQL Audit Plugin** o **AWS RDS Enhanced Monitoring** para registrar todas las queries.
3. Implementar **separación de ambientes**: los desarrolladores no tienen acceso a producción por defecto.
4. Activar **MFA** para acceso a AWS Console y secrets.
5. Configurar alertas en CloudTrail para: acceso a secrets en horario inusual, queries masivos, cambios de IAM.
6. Aplicar **Breakglass Access** para emergencias: acceso temporal con doble aprobación y auditoría completa.

---

### R-S04 — Compromiso de Dependencias (Supply Chain)
**Probabilidad**: MEDIA | **Impacto**: MEDIO | **Severidad**: 🟡 Media | **Estado**: ⚠️ Parcial

**Descripción**: Una dependencia de Maven maliciosa o comprometida (`log4shell`, `xz-utils`) puede afectar al servicio. Con ~80 dependencias directas y transitivas, la superficie de ataque es significativa.

**Indicador de detección**: CVE publicado para una librería usada en el proyecto.

**Mitigación aplicada**:
- OWASP Dependency Check en el pipeline de CI (`mvn dependency-check:check`).

**Acciones pendientes**:
1. Habilitar **GitHub Dependabot** o **Snyk** para alertas automáticas de CVEs en dependencias.
2. Usar **Maven Enforcer Plugin** para prohibir versiones con CVEs conocidos (`<bannedDependencies>`).
3. Configurar **Artifactory / Nexus** como proxy de Maven con escaneo de artefactos antes de cachear.
4. Pinear versiones exactas de todas las dependencias (evitar rangos `[1.0,2.0)` en producción).
5. Suscribirse a boletines de seguridad de Spring, MySQL Connector y Resilience4j.

---

## Riesgos de Cumplimiento

### R-B01 — Incumplimiento Regulatorio (SFC / Habeas Data / PCI DSS)
**Probabilidad**: ALTA | **Impacto**: ALTO | **Severidad**: 🔴 Crítica | **Estado**: ⚠️ Parcial

**Descripción**: Como plataforma de pagos en Colombia, TumiPay opera bajo:
- **Circular 052/2007 SFC**: estándares de seguridad para operaciones en banca electrónica.
- **Ley 1581/2012**: protección de datos personales (Habeas Data).
- **PCI DSS**: aplica si se procesan datos de tarjetas de crédito directamente.
- **SAGRILAFT**: sistema de autocontrol contra lavado de activos.

**Indicador de detección**: Visita de inspección de la SFC, queja formal de un usuario, brecha de datos reportada.

**Acciones de mitigación**:
1. Contratar **asesoría legal especializada** en fintech colombiano antes del go-live.
2. Implementar **registro de auditoría inmutable** de todas las operaciones (quién, qué, cuándo) — tabla `audit_events` en BD separada.
3. Definir y publicar **Política de Privacidad** y **Aviso de Privacidad** conforme a Ley 1581.
4. Implementar **mecanismo de revocación de consentimiento** (derecho de supresión de datos).
5. Si se procesan tarjetas directamente: iniciar proceso de **certificación PCI DSS SAQ-A o SAQ-D** con un QSA certificado.
6. Establecer **programa SAGRILAFT**: señales de alerta de LA/FT, listas restrictivas (OFAC, ONU, UIAF).

---

### R-B02 — Fraude y Lavado de Activos (AML/CFT)
**Probabilidad**: ALTA | **Impacto**: ALTO | **Severidad**: 🔴 Crítica | **Estado**: ❌ Sin mitigar

**Descripción**: La plataforma puede ser usada para fraccionar transacciones grandes (smurfing), crear transacciones ficticias entre cuentas controladas, o probar tarjetas robadas a pequeña escala (carding). Sin controles de AML, TumiPay es cómplice pasivo.

**Indicador de detección**: Patrones inusuales: muchas transacciones pequeñas desde el mismo IP, mismo merchant con múltiples documentos distintos, rechazos de proveedor por tarjetas inválidas.

**Acciones de mitigación**:
1. Implementar **motor de reglas antifraude** (score por transacción):
   - Velocidad: > 5 transacciones por minuto desde mismo merchant → alerta.
   - Umbral: transacciones fraccionadas que suman > umbral de reporte UIAF (actualmente $10M COP).
   - Geolocalización: IP del request vs. país declarado en la transacción.
2. Integrar con **listas restrictivas** (OFAC SDN, listas ONU, listas UIAF Colombia).
3. Implementar **reporte automático de operaciones sospechosas** (ROS) a la UIAF cuando se detecten patrones AML.
4. Agregar campo `risk_score` en la transacción, calculado antes de enviar al proveedor.
5. Revisar con asesoría legal el umbral de reporte en efectivo y operaciones inusuales.

---

### R-B03 — Cobro Doble al Usuario Final
**Probabilidad**: ALTA | **Impacto**: ALTO | **Severidad**: 🔴 Crítica | **Estado**: ⚠️ Parcial

**Descripción**: Si hay un timeout en la llamada al proveedor y el sistema reintenta (Resilience4j Retry), el proveedor puede haber procesado el primer intento exitosamente mientras el retry crea un segundo cobro. El usuario final ve dos débitos.

**Indicador de detección**: Reclamación del usuario, divergencia entre transacciones en estado `APPROVED` y transacciones confirmadas por el proveedor.

**Mitigación aplicada**:
- Idempotencia de 3 capas (Redis + DB + @Version) previene duplicados en el lado del orchestrator.
- Resilience4j Retry ignora `ValidationException` y `DuplicateTransactionException`.

**Acciones pendientes**:
1. Al llamar al proveedor, **enviar `client_transaction_id` como idempotency key del proveedor** (`Idempotency-Key` header en PSE/Nequi). Si el proveedor lo soporta, garantiza que el reintento devuelve el resultado del primer intento.
2. Antes de reintentar, **consultar estado de la transacción en el proveedor** (`GET /provider/transaction/{id}`) para verificar si ya fue procesada.
3. Implementar **reconciliación diaria automatizada**: comparar todas las transacciones `APPROVED` del orchestrator contra el reporte de transacciones del proveedor.
4. Definir **SLA de resolución de disputas** (< 24h para doble cobro) y proceso de reembolso documentado.

---

## Riesgos Operacionales

### R-O01 — Sin Plan de Recuperación ante Desastre (DRP)
**Probabilidad**: BAJA | **Impacto**: BAJO | **Severidad**: 🟢 Baja | **Estado**: ❌ Sin mitigar

**Descripción**: No existe un DRP documentado ni probado. En caso de pérdida total del ambiente (AWS region down, eliminación accidental de recursos), el tiempo de recuperación es desconocido.

**Acciones de mitigación**:
1. Documentar **RTO** (Recovery Time Objective) y **RPO** (Recovery Point Objective) acordados con el negocio.
2. Configurar **backups automatizados de RDS** con restauración verificada mensualmente.
3. Mantener **IaC (Terraform)** del ambiente completo para poder recrear en otra región en < 2 horas.
4. Ejecutar **simulacro de recuperación** semestral (chaos engineering controlado).

---

### R-O02 — Error de Configuración del Monto Máximo
**Probabilidad**: BAJA | **Impacto**: MEDIO | **Severidad**: 🟢 Baja | **Estado**: ✅ Mitigado

**Descripción**: Si `MAX_AMOUNT_CENTS` se configura incorrectamente (ej: `0` o un valor extremadamente alto), se pueden bloquear todas las transacciones o permitir montos anómalos.

**Mitigación aplicada**:
- Validación en `TransactionDomainValidator`: `amount > 0 AND amount ≤ maxAmountCents`.
- Default seguro: `10,000,000,000` centavos = $100,000,000 COP.

**Acciones pendientes**:
1. Agregar validación al arrancar la aplicación (`@PostConstruct`) que verifique `maxAmountCents > 0`.
2. Implementar alerta si `maxAmountCents` cambia más de un 10x respecto al valor anterior (cambio anómalo de configuración).

---

### R-O03 — Caída Total del Proveedor de Pago
**Probabilidad**: MEDIA | **Impacto**: ALTO | **Severidad**: 🟠 Alta | **Estado**: ❌ Sin mitigar

**Descripción**: Si PSE (o el proveedor principal) cae, todas las transacciones de ese método de pago fallan. El Circuit Breaker abre, pero no existe proveedor de respaldo configurado.

**Indicador de detección**: Circuit Breaker en estado `OPEN` por > 1 minuto, tasa de error > 50%.

**Acciones de mitigación**:
1. Implementar **proveedor de fallback** por método de pago: si PSE falla, intentar con una pasarela alternativa compatible.
2. Exponer **endpoint de estado de proveedores** (`GET /api/v1/providers/health`) para que el merchant pueda mostrar métodos disponibles en su checkout.
3. Configurar **notificación automática al merchant** cuando su método de pago principal queda indisponible.
4. Suscribirse a las **páginas de status de cada proveedor** (status.pse.com.co, etc.) e integrar con PagerDuty.

---

### R-O04 — Drift de Configuración entre Entornos
**Probabilidad**: MEDIA | **Impacto**: MEDIO | **Severidad**: 🟡 Media | **Estado**: ❌ Sin mitigar

**Descripción**: Con el tiempo, las configuraciones de `dev`, `staging` y `prod` divergen. Un parámetro de seguridad (ej: `API_KEYS` con keys de prueba en prod) puede enmascarar vulnerabilidades durante el desarrollo.

**Acciones de mitigación**:
1. Usar **GitOps**: todas las configuraciones de todos los entornos versionadas en el mismo repositorio bajo `/config/{env}/`.
2. Implementar **Config Drift Detector**: script que compara keys de configuración entre entornos y alerta si hay diferencias no esperadas.
3. Revisar configuraciones de seguridad en **checklist de pre-deploy** a producción.
4. Usar **Spring Cloud Config Server** para centralizar y auditar cambios de configuración.

---

## Riesgos de Terceros

### R-P01 — Cambios de API del Proveedor sin Aviso
**Probabilidad**: MEDIA | **Impacto**: ALTO | **Severidad**: 🟠 Alta | **Estado**: ❌ Sin mitigar

**Descripción**: Un proveedor actualiza su API (nueva versión, campos obligatorios, cambio de autenticación) sin aviso suficiente. Los adaptadores dejan de funcionar en producción.

**Indicador de detección**: Incremento súbito de errores `PaymentProviderException` en logs, Circuit Breaker en OPEN.

**Acciones de mitigación**:
1. Establecer **contrato formal SLA de notificación de cambios** (mínimo 30 días de aviso) con cada proveedor.
2. Implementar **tests de contrato** (Pact) entre el adaptador y el mock del proveedor, ejecutados en CI.
3. Mantener **canal de comunicación directo** con el equipo técnico de cada proveedor.
4. Versionar las integraciones: `PsePaymentProviderAdapterV1`, `PsePaymentProviderAdapterV2` conviven durante migración.
5. Monitorear el **endpoint de health del proveedor** y sus changelogs / release notes automáticamente.

---

### R-P02 — Fallo de Entrega de Webhooks al Merchant
**Probabilidad**: MEDIA | **Impacto**: MEDIO | **Severidad**: 🟡 Media | **Estado**: ❌ Sin mitigar

**Descripción**: Cuando el estado de una transacción cambia (APPROVED/REJECTED), el orchestrator debe notificar al merchant via webhook. Si el endpoint del merchant está caído, la notificación se pierde y el merchant no libera el pedido.

**Indicador de detección**: Respuestas HTTP 5xx al `webhook_url` del merchant; transacciones en estado final sin notificación confirmada.

**Acciones de mitigación**:
1. Implementar **Outbox Pattern**: registrar el evento de notificación en la misma transacción de BD que actualiza el estado.
2. **Relay service con reintento exponencial**: 1s → 2s → 4s → 8s → 16s → 30s → 60s (máximo 10 intentos en 24h).
3. Si todos los reintentos fallan, **notificar al merchant por email** como fallback y marcar el evento como `EXHAUSTED`.
4. Exponer **endpoint de consulta de estado** (`GET /api/v1/transactions/{id}`) para que el merchant pueda hacer polling como alternativa.
5. Registrar en `audit_events` cada intento de entrega: timestamp, HTTP status, respuesta, número de intento.

---

## Next Steps

This section maps the evolution path from the current MVP to a production-grade, horizontally scalable payment platform. Items are grouped by time horizon and labeled with priority.

### Legend

| Symbol | Meaning |
|---|---|
| 🔴 **Crítico** | Bloqueante para go-live en producción |
| 🟠 **Alto** | Requerido antes de escalar horizontalmente |
| 🟡 **Medio** | Mejora significativa, planificable en sprints |
| 🟢 **Bajo** | Nice-to-have / deuda técnica controlada |

---

### Fase 1 — MVP → Producción (Sprint 1-3)

Estas tareas deben completarse **antes del primer despliegue productivo**. Sin ellas, el sistema tiene brechas funcionales o de seguridad inaceptables.

#### 🔴 1.1 Receptor de Webhooks del Proveedor (`WebhookController`)

El riesgo más alto del sistema actual (R-003). Las transacciones quedan en estado `PROCESSING` indefinidamente porque no existe un endpoint que reciba la notificación asíncrona del proveedor.

**Qué implementar:**
```
POST /api/v1/webhooks/{provider}
    │
    ├─ Verificar firma HMAC del proveedor (header: X-Webhook-Signature)
    ├─ Idempotencia: ignorar duplicados (mismo event_id ya procesado)
    ├─ UpdateTransactionStatusUseCase
    │    ├─ Cargar transacción por provider_transaction_id
    │    ├─ Validar transición de estado (PROCESSING → APPROVED/REJECTED)
    │    ├─ Persistir con @Version (optimistic locking)
    │    └─ Emitir evento de dominio (notificar al merchant)
    └─ Responder 200 inmediatamente (procesamiento asíncrono)
```

**Job de expiración** (complementario): batch que marque como `EXPIRED` las transacciones en `PROCESSING` pasado su `expiration_time`. Ejecutar cada 5 minutos vía `@Scheduled`.

---

#### 🔴 1.2 Adaptadores de Proveedores Reales

Reemplazar `MockPaymentProvider` con adaptadores reales. Cada uno implementa `PaymentProviderPort` — sin cambios al core.

| Proveedor | Tipo | Complejidad | Prioridad |
|---|---|---|---|
| **PSE** (ACH Colombia) | Redirect flow | Media | Sprint 1 |
| **Nequi** | Wallet push | Alta | Sprint 2 |
| **Bre-B** | Real-time rail | Alta | Sprint 2 |
| **Efecty** | Cash voucher | Media | Sprint 3 |
| **Tarjetas** (Redeban/Credibanco) | Card processing | Muy alta | Sprint 4 |

**Estructura por adaptador:**
```
infrastructure/
  outbound/
    payment/
      pse/
        PsePaymentProviderAdapter.java     ← implements PaymentProviderPort
        PseHttpClient.java                 ← WebClient + Resilience4j
        PseRequestMapper.java              ← domain → PSE DTO
        PseResponseMapper.java             ← PSE response → domain
        dto/
          PsePaymentRequest.java
          PsePaymentResponse.java
```

---

#### 🔴 1.3 Gestión de Secretos (Secrets Manager)

Las API keys y credenciales de BD **no deben vivir en variables de entorno planas** en producción.

**Migrar a:**
```
AWS Secrets Manager  →  Spring Cloud AWS Secrets Manager Starter
HashiCorp Vault      →  Spring Cloud Vault
```

**Beneficios concretos:**
- Rotación de keys sin redeployment
- Auditoría de acceso a secretos
- Cifrado en reposo y en tránsito
- RBAC por rol/servicio

**Rotación de API keys sin downtime** (diseño sugerido):
```yaml
# En lugar de: api-keys: "key-A,key-B"
# Soportar versiones activas simultáneas durante rotación:
api-keys:
  active:   ["key-new-2026"]
  retiring: ["key-old-2025"]   # Acepta aún, pero loga WARNING
  revoked:  []                 # Rechaza con 401 + mensaje específico
```

---

#### 🔴 1.4 Tests de Integración con Testcontainers

Los tests actuales usan H2 in-memory, que diverge de MySQL en comportamiento de índices, collation y Flyway. Esto es R-002.

```java
@SpringBootTest
@Testcontainers
class TransactionPersistenceIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("tumipay_orchestrator");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Test
    void shouldPersistTransactionAndPreventDuplicate() { ... }
}
```

**Cobertura mínima para go-live:**
- `CreateTransactionService` end-to-end (happy path + idempotencia)
- `ApiKeyAuthFilter` (IP whitelist + key validation)
- `CorrelationIdFilter` (propagación de correlation ID en headers y MDC)
- Flyway migrations contra MySQL real

---

#### 🔴 1.5 Brute-Force Protection (`BruteForceProtectionService`)

Actualmente no existe protección contra intentos repetidos de autenticación. Un atacante puede probar API keys de forma indefinida (R-T05). Este control es bloqueante para producción.

**Qué implementar:**
```java
// infrastructure/security/BruteForceProtectionService.java
@Service
public class BruteForceProtectionService {

    // Redis: INCR + EXPIRE atómicos para contadores distribuidos
    public boolean recordFailedAttempt(String ip) {
        String key = "bf:attempts:" + ip;
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts == 1) {
            redisTemplate.expire(key, attemptWindowMinutes, TimeUnit.MINUTES);
        }
        if (attempts >= maxAttempts) {
            redisTemplate.opsForValue().set("bf:blocked:" + ip, "1",
                lockoutMinutes, TimeUnit.MINUTES);
            return true; // IP ahora bloqueada
        }
        return false;
    }

    public boolean isBlocked(String ip) {
        return Boolean.TRUE.equals(
            redisTemplate.hasKey("bf:blocked:" + ip));
    }

    public void recordSuccessfulAuth(String ip) {
        redisTemplate.delete("bf:attempts:" + ip);
    }
}
```

**Integración en `ApiKeyAuthFilter`** (antes de la validación de clave):
```java
// Paso 0 — bloqueo por IP (antes de consumir recursos de validación)
if (bruteForceProtection.isBlocked(clientIp)) {
    log.warn("security.auth_blocked_by_brute_force ip={}", clientIp);
    writeTooManyRequests(response, "Too many failed attempts. Try again later.", lockoutSeconds);
    return;
}
```

**Variables de entorno a agregar:**
| Variable | Default | Descripción |
|---|---|---|
| `BRUTE_FORCE_MAX_ATTEMPTS` | `10` | Intentos fallidos antes del bloqueo |
| `BRUTE_FORCE_WINDOW_MIN` | `5` | Ventana de observación (minutos) |
| `BRUTE_FORCE_LOCKOUT_MIN` | `15` | Duración del bloqueo (minutos) |

**Usar Redis** desde el inicio — así los contadores son distribuidos y funcionan correctamente con múltiples réplicas en Kubernetes.

---

### Fase 2 — Escala Horizontal (Sprint 4-6)

Una vez en producción con una sola instancia, estas tareas permiten escalar a múltiples réplicas en Kubernetes sin pérdida de consistencia en los controles de seguridad.

#### 🟠 2.1 Rate Limiting (Fase 2)

El servicio actualmente no tiene rate limiting. Para escala horizontal se recomienda implementarlo con **Bucket4j + Redis** para que los contadores sean distribuidos entre réplicas:

```java
// RateLimitingFilter — Token Bucket por API key, backend Redis
RedisBasedProxyManager<String> proxyManager =
    Bucket4jRedis.casBasedBuilder(redissonClient).build();

Bucket bucket = proxyManager.builder()
    .addLimit(Bandwidth.classic(100, Refill.intervally(100, Duration.ofMinutes(1))))
    .build(apiKey);

if (!bucket.tryConsume(1)) {
    response.setStatus(429);
    response.setHeader("Retry-After", "60");
    return;
}
```

---

#### 🟠 2.2 Kubernetes — HPA y Configuración de Probes

**HorizontalPodAutoscaler** basado en CPU:
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: transaction-orchestrator-hpa
spec:
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

**Liveness / Readiness / Startup probes** (ya habilitadas en `application.yml`):
```yaml
# Kubernetes deployment.yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  failureThreshold: 30
  periodSeconds: 5
```

---

#### 🟠 2.3 Persistencia — Alta Disponibilidad

| Componente | Actual | Producción |
|---|---|---|
| MySQL | Single instance | AWS RDS Multi-AZ + Read Replica |
| Redis | Single instance | AWS ElastiCache (cluster mode, 3 shards) |
| Conexiones | HikariCP 20 max | Separar pool escritura / lectura |
| Backup | Manual | RDS automated backup + point-in-time recovery |

**Read replica para consultas** (ajuste en `application.yml`):
```yaml
spring:
  datasource:
    # Escritura → primaria
    url: ${DB_PRIMARY_URL}
  datasource-read:
    # Lectura → réplica (GetTransactionService)
    url: ${DB_REPLICA_URL}
```

---

### Fase 3 — Hardening de Seguridad (Sprint 7-9)

#### 🟡 3.1 mTLS entre Servicios Internos

Agregar autenticación mutua TLS para llamadas inter-servicio (orchestrator → provider adapters, orchestrator → webhook dispatcher).

```
Orquestador ──[mTLS]──► Adaptador PSE
            ──[mTLS]──► Adaptador Nequi
            ──[mTLS]──► Webhook Dispatcher
```

**Stack sugerido:** Istio service mesh + SPIFFE/SPIRE para rotación automática de certificados.

---

#### 🟡 3.2 Protección contra DNS Rebinding (R-006)

La `SsrfGuard` actual valida la URL en tiempo de recepción del request, pero no resuelve DNS. Un atacante puede:
1. Registrar `evil.com` apuntando a `1.2.3.4` (pública) → pasa validación.
2. Cambiar DNS para apuntar a `169.254.169.254` → el HTTP client llama al metadata service.

**Fix en el HTTP client que ejecuta los webhooks:**
```java
// WebClientConfig.java — validar IP post-resolución DNS
.clientConnector(new ReactorClientHttpConnector(
    HttpClient.create()
        .resolver(nameResolverSpec ->
            nameResolverSpec.resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED))
        .doOnConnected(conn ->
            validateResolvedIp(conn.address()))   // ssrfGuard.validateIp()
))
```

---

#### 🟡 3.3 WAF (Web Application Firewall)

Delante del Load Balancer, bloquear:
- Inyección SQL / NoSQL en headers
- XSS en campos de texto libre
- Geo-blocking por país (si el negocio aplica)
- Bot signatures conocidos

**Opciones:** AWS WAF v2 (recomendado para AWS), Cloudflare WAF, NGINX ModSecurity.

---

#### 🟡 3.4 Auditoría y Trazabilidad Regulatoria

Para cumplimiento con **Habeas Data (Ley 1581/2012)**, **SFC**, y eventualmente **PCI DSS**:

```java
// AuditEventEntity.java — tabla separada de auditoría
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {
    UUID id;
    String eventType;         // TRANSACTION_CREATED, STATUS_UPDATED, AUTH_FAILED
    String actorKey;          // API key (hasheada)
    String resourceId;        // transaction_id
    String clientIp;
    Instant occurredAt;
    String payload;           // JSON diff del cambio (sin PII)
}
```

**Publicar en Kafka / SQS** para consumo por un servicio de auditoría dedicado — no escribir en la misma BD transaccional.

---

### Fase 4 — Evolución del Producto (Sprint 10+)

#### 🟢 4.1 Multi-Tenancy y Configuración por Cliente

```
API Key A (Merchant X) → límite $5.000.000 COP, solo PSE, solo CO
API Key B (Merchant Y) → límite $100.000.000 COP, PSE + Nequi, CO + MX
```

Migrar la configuración estática de `application.yml` a una tabla `client_configurations` con cache en Redis.

---

#### 🟢 4.2 Event Sourcing para Estado de Transacciones

En lugar de sobreescribir el estado (`PENDING → PROCESSING → APPROVED`), registrar cada transición como un evento inmutable:

```
TransactionCreatedEvent
TransactionRoutedEvent
TransactionApprovedEvent
TransactionRefundedEvent
```

**Beneficios:** auditoría completa, replay para debugging, compatible con CQRS para proyecciones de reportes.

---

#### 🟢 4.3 Notificaciones al Merchant (Outbox Pattern)

Para evitar pérdida de notificaciones si el webhook hacia el merchant falla:

```
┌─────────────────────────────────────────────┐
│  @Transactional                             │
│  1. UPDATE transaction SET status=APPROVED  │
│  2. INSERT INTO outbox_events (payload)     │  ← misma transacción
└─────────────────────────────────────────────┘
         │
         ▼ (Polling job cada 1s)
    Outbox Relay Service
         │
         ▼
    POST merchant_webhook_url   (con retry exponencial)
```

---

#### 🟢 4.4 Monitoreo Avanzado y SLAs

| Alerta | Condición | Acción |
|---|---|---|
| Circuit breaker OPEN | `cb_state == "OPEN"` | Alerta inmediata |
| Transacciones PROCESSING > 10 min | Query periódica | Job de expiración |
| `security.invalid_api_key` > 10/min por IP | Log pattern en agregador | Alerta + bloqueo manual hasta implementar R-T05 |

---

### Roadmap Visual

```
Hoy (MVP)
    │
    ▼─────────────────── Sprint 1-3 ──────────────────────▶
    │  🔴 Webhook receiver + job de expiración
    │  🔴 Adaptador PSE real
    │  🔴 Secrets Manager (Vault / AWS SM)
    │  🔴 Tests integración (Testcontainers + MySQL)
    │  🔴 Brute-force protection (BruteForceProtectionService + Redis)
    │
    ▼─────────────────── Sprint 4-6 ──────────────────────▶
    │  🟠 Rate limiting → Redis distribuido (Bucket4j Redis)
    │  🟠 Kubernetes HPA + probes tuning
    │  🟠 MySQL Multi-AZ + Redis ElastiCache cluster
    │  🟠 Adaptadores Nequi y Bre-B
    │
    ▼─────────────────── Sprint 7-9 ──────────────────────▶
    │  🟡 mTLS inter-servicio (Istio / SPIFFE)
    │  🟡 DNS rebinding fix en HTTP client de webhooks
    │  🟡 WAF delante del Load Balancer
    │  🟡 Tabla de auditoría + Kafka → servicio regulatorio
    │
    ▼─────────────────── Sprint 10+ ───────────────────────▶
       🟢 Multi-tenancy con config por cliente en BD
       🟢 Event Sourcing para estado de transacciones
       🟢 Outbox Pattern para notificaciones garantizadas
       🟢 Dashboards Grafana
       🟢 PCI DSS SAQ-A assessment
```
