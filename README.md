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
- [Logging & Health](#logging--health)
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
                         │             TumiPay Transaction Orchestrator                 │
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
                         │  │              HTTP Inbound Adapter                       │ │
                         │  │  REST Controller + Bean Validation + MapStruct DTOs     │ │
                         │  └───────────────────────┬─────────────────────────────────┘ │
                         │                          │                                   │
                         │                          ▼                                   │
                         │  ┌─────────────────────────────────────────────────────────┐ │
                         │  │              APPLICATION CORE                           │ │
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
                         │         ┌────────────────┴───────────────┐                   │
                         │         ▼                                ▼                   │
                         │  ┌──────────────┐           ┌────────────────────────┐       │
                         │  │   MySQL 8    │           │  Redis 7               │       │
                         │  │  (Primary DB)│           │  Idempotency + Cache   │       │
                         │  └──────────────┘           └────────────────────────┘       │
                         └──────────────────────────────────────────────────────────────┘
```

### Transaction Flow (7 Steps)

```
Client → POST /api/v1/transactions  (X-API-Key: <key>)
             │
             ▼
┌────────────────────────────────────────────────────┐
│  Security Filter Chain                             │
│  ① Correlation ID + client IP → MDC                │
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

## Logging & Health

### Endpoints

| URL | Auth required | Purpose |
|---|---|---|
| `http://localhost:8080` | YES (`X-API-Key`) | Main API |
| `http://localhost:8080/swagger-ui.html` | NO | Interactive API docs |
| `http://localhost:8080/actuator/health` | NO | DB + Redis health probes |

### Health Check

`GET /actuator/health` returns composite health via Spring Boot Actuator:
- **db**: HikariCP connection pool status
- **redis**: PING response

```json
{
  "status": "UP",
  "components": {
    "db":    { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

### Structured Logging

Profile-based configuration (`logback-spring.xml`):

| Profile | Format | Destination |
|---|---|---|
| `default`, `local`, `test` | Colored console | stdout |
| `prod`, `docker`, `staging` | JSON (Logstash encoder) | stdout → log aggregator |

Every log line carries MDC fields: `correlation_id`, `client_ip`, `client_transaction_id`.

> **Not implemented**: Prometheus metrics, Grafana dashboards, and distributed tracing (Zipkin/Jaeger) are listed as next steps but are not part of this version.

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
│  Sonar   │  │  (main/master/develop)   │
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
| `master` | YES | YES | YES |
| PR to main/master/develop | YES | NO | NO |

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

| Tool | Version | Install |
|---|---|---|
| Git | any | [git-scm.com](https://git-scm.com) |
| Docker Desktop | 24+ | [docker.com/get-started](https://www.docker.com/get-started/) |
| Docker Compose | included in Docker Desktop | — |

> Java and Maven are **not required** to run the full stack — the Docker build compiles the app inside the container.

---

### Quickstart — Full Stack with Docker (recommended)

#### Step 1 — Clone the repository

```bash
git clone https://github.com/felipevasquez74/transaction-orchestrator.git
cd transaction-orchestrator
```

#### Step 2 — Build and start all services

```bash
docker-compose up --build -d
```

This single command:
1. Compiles the Java application inside Docker (no local JDK needed)
2. Starts **MySQL 8** and waits for it to be healthy
3. Starts **Redis 7** and waits for it to be healthy
4. Starts the **Orchestrator** on port `8080`

First build takes ~3–5 minutes (downloads Maven dependencies). Subsequent builds are faster due to Docker layer caching.

#### Step 3 — Verify the stack is up

```bash
# Check all containers are running
docker-compose ps

# Expected output:
# NAME                   STATUS
# tumipay-mysql          Up (healthy)
# tumipay-redis          Up (healthy)
# tumipay-orchestrator   Up

# Health endpoint (no auth required)
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
```

#### Step 4 — Open Swagger UI

```
http://localhost:8080/swagger-ui.html
```

All endpoints are documented and executable directly from the browser. Use `test-key-docker-001` as the API key in the Authorize button.

#### Step 5 — Send a test transaction

```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key-docker-001" \
  -H "X-Correlation-ID: test-001" \
  -d '{
    "client_transaction_id": "ORDER-DOCKER-001",
    "amount": 150000,
    "currency_code": "COP",
    "country_code": "CO",
    "payment_method_id": "PSE",
    "webhook_url": "https://webhook.site/your-unique-id",
    "redirect_url": "https://example.com/result",
    "description": "Test payment via Docker",
    "customer": {
      "first_name": "Juan",
      "last_name": "Pérez",
      "email": "juan@example.com",
      "document_type": "CC",
      "document_number": "1234567890"
    }
  }'
```

Expected response `HTTP 201`:
```json
{
  "code": "000",
  "message": "Successful operation",
  "data": {
    "transaction_id": "<uuid>",
    "status": "PROCESSING",
    "client_transaction_id": "ORDER-DOCKER-001",
    "payment_method_id": "PSE",
    "currency_code": "COP",
    "country_code": "CO"
  }
}
```

#### Step 6 — View logs (optional)

```bash
# Follow live application logs
docker-compose logs -f orchestrator

# View MySQL query log
docker-compose logs -f mysql
```

#### Step 7 — Stop the stack

```bash
# Stop containers (keeps DB data)
docker-compose down

# Stop and wipe all data (clean slate for next run)
docker-compose down -v
```

---

### Option 2: Local Development (MySQL + Redis via Docker, app via Maven)

Requires Java 17+ and Maven 3.9+ installed locally.

```bash
# Start only infrastructure
docker-compose up mysql redis -d

# Run application with local profile
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Use API key `test-key-dev-001` for local development.

---

### Option 3: Tests Only

```bash
# Unit tests (H2 in-memory — no Docker needed)
mvn test

# Full build + test + JaCoCo coverage report
mvn clean verify
# Coverage report: target/site/jacoco/index.html
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
# Create a transaction (Docker stack — use test-key-docker-001)
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key-docker-001" \
  -H "X-Correlation-ID: test-001" \
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

# Query a transaction by ID
curl -H "X-API-Key: test-key-docker-001" \
  http://localhost:8080/api/v1/transactions/<transaction_id>

# Health probe (no auth required)
curl http://localhost:8080/actuator/health
```

> **Local development** (Option 2): use `test-key-dev-001` instead.

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

### ADR-008: Correlation ID in MDC
**Decision**: `CorrelationIdFilter` generates or reuses the `X-Correlation-ID` and publishes it to SLF4J MDC so that all logs for the request carry the same trace ID.  
**Rationale**: Without a shared correlation ID, correlating logs for the same request is impossible in concurrent environments.  
**Consequence**: MDC is cleared in `finally` to prevent leaks between requests in the thread pool.

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

7. **Brute-force protection**: Not yet implemented. Authentication failures are logged but no automatic IP lockout is in place. This is tracked as a pending next step (see [Next Steps — Phase 1](#phase-1--mvp--production-sprint-1-3)).

8. **UTF-8 encoding**: All string data stored as `utf8mb4` in MySQL, supporting full Unicode.

---

## Identified Risks & Mitigation Plan

This section documents all identified risks across five categories: Technical, Security, Compliance, Operational, and Third-Party. Each risk includes probability, impact, severity, detection indicators, and concrete mitigation actions.

### Risk Matrix — Executive Summary

```
IMPACT
  │
  │  HIGH  │ R-S02  │ R-T03  R-T07 │ R-T06  R-B01  R-B02 │
  │        │ R-O03  │ R-S01  R-P01 │ R-B03  R-S03        │
  │        │        │              │                      │
  │ MEDIUM │ R-T02  │ R-T05        │                      │
  │        │ R-T01  │ R-S04  R-O04 │                      │
  │        │ R-P02  │              │                      │
  │        │        │              │                      │
  │  LOW   │ R-O01  │ R-O02        │                      │
  │        │        │              │                      │
  └────────┴────────┴──────────────┴──────────────────────▶ PROBABILITY
               LOW         MEDIUM             HIGH
```

| Severity | Color | Criterion |
|---|---|---|
| 🔴 **Critical** | Red | High probability × High impact — requires immediate action |
| 🟠 **High** | Orange | Medium probability × High impact, or High × Medium |
| 🟡 **Medium** | Yellow | Medium probability × Medium impact |
| 🟢 **Low** | Green | Low probability or low impact |

---

### Consolidated Table

| ID | Risk | Category | Prob. | Impact | Severity | Status |
|---|---|---|---|---|---|---|
| R-T03 | Missing webhook receiver | Technical | HIGH | HIGH | 🔴 Critical | Pending |
| R-B01 | Regulatory non-compliance SFC / Habeas Data | Compliance | HIGH | HIGH | 🔴 Critical | Partial |
| R-B02 | Fraud / Money laundering | Compliance | HIGH | HIGH | 🔴 Critical | Unmitigated |
| R-B03 | Double charge to end user | Business | HIGH | HIGH | 🔴 Critical | Partial |
| R-T06 | DNS Rebinding in webhook URLs | Security | HIGH | HIGH | 🔴 Critical | Documented |
| R-S01 | API key leak / compromise | Security | HIGH | HIGH | 🔴 Critical | Partial |
| R-T07 | MySQL single point of failure | Technical | MEDIUM | HIGH | 🟠 High | Unmitigated |
| R-P01 | Provider API changes without notice | Third-Party | MEDIUM | HIGH | 🟠 High | Unmitigated |
| R-S03 | Insider threat | Security | MEDIUM | HIGH | 🟠 High | Unmitigated |
| R-O03 | Total payment provider outage | Operational | MEDIUM | HIGH | 🟠 High | Unmitigated |
| R-S02 | PII in logs | Security | LOW | HIGH | 🟠 High | Mitigated |
| R-T05 | Brute-force protection not implemented | Technical | HIGH | MEDIUM | 🟠 High | Pending |
| R-S04 | Dependency compromise (supply chain) | Security | MEDIUM | MEDIUM | 🟡 Medium | Partial |
| R-T02 | Schema divergence test vs production | Technical | MEDIUM | MEDIUM | 🟡 Medium | Pending |
| R-T01 | Coupling with new provider | Technical | LOW | MEDIUM | 🟡 Medium | Mitigated |
| R-O04 | Configuration drift between environments | Operational | MEDIUM | MEDIUM | 🟡 Medium | Unmitigated |
| R-P02 | Webhook delivery failure to merchant | Third-Party | MEDIUM | MEDIUM | 🟡 Medium | Unmitigated |
| R-O02 | Maximum amount misconfiguration | Operational | LOW | MEDIUM | 🟢 Low | Mitigated |
| R-O01 | No disaster recovery plan | Operational | LOW | LOW | 🟢 Low | Unmitigated |

---

## Technical Risks

### R-T01 — Coupling with New Provider
**Probability**: LOW | **Impact**: MEDIUM | **Severity**: 🟡 Medium | **Status**: ✅ Mitigated

**Description**: Adding a new payment provider (Nequi, Bre-B, PSE) requires implementing a complete adapter with an HTTP client, error handling, and retry logic.

**Detection indicator**: New provider integration time > 2 weeks.

**Applied mitigation**:
- `PaymentProviderPort` defines a minimal and stable contract (Hexagonal Architecture).
- Adapters are developed independently without modifying the core.
- `MockPaymentProvider` serves as an implementation reference.

**Pending actions**:
1. Document the adapter implementation guide with a checklist.
2. Create a test adapter (`EchoPaymentProvider`) that mirrors the request as a response, useful for merchant testing in sandbox.

---

### R-T02 — Schema Divergence Test vs Production
**Probability**: MEDIUM | **Impact**: MEDIUM | **Severity**: 🟡 Medium | **Status**: ⏳ Pending

**Description**: Unit tests use H2 in-memory with schema generated by Hibernate (`ddl-auto: create`). MySQL 8 with Flyway may behave differently in collation, data types, and foreign key constraints. A bug can pass tests and fail in production.

**Detection indicator**: `FlywayMigrationException` or `DataIntegrityViolationException` error on staging deployment that did not occur in tests.

**Mitigation actions**:
1. Add integration tests with **Testcontainers** (`mysql:8.0`) that execute the real Flyway migrations.
2. Separate unit tests (H2) from integration tests (MySQL) using Maven profiles (`-P integration`).
3. Run integration tests in the CI pipeline on the `develop` branch.
4. Add `@FlywayTest` annotation for automatic rollback between integration tests.

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
        // If Spring starts, migrations passed
    }
}
```

---

### R-T03 — Missing Provider Webhook Receiver
**Probability**: HIGH | **Impact**: HIGH | **Severity**: 🔴 Critical | **Status**: ❌ Pending

**Description**: There is no endpoint to receive asynchronous notifications from the provider. Transactions remain in `PROCESSING` state indefinitely. Without status resolution, the merchant cannot confirm the payment or release the order.

**Detection indicator**:
- Alert: `COUNT(*) WHERE status = 'PROCESSING' AND created_at < NOW() - INTERVAL 10 MINUTE > 0`
- Dashboard: rate of transactions that never leave `PROCESSING`.

**Mitigation actions** (blocking for production):
1. Implement `POST /api/v1/webhooks/{provider}` with HMAC signature verification.
2. Implement `UpdateTransactionStatusUseCase` with state transition validation (`PROCESSING → APPROVED/REJECTED`).
3. Use idempotency in the receiver: ignore already-processed `event_id` values (Redis SET NX).
4. Add `@Scheduled` job every 5 minutes to mark as `EXPIRED` those transactions stuck in `PROCESSING` past their `expiration_time`.
5. Emit event to the merchant (outbound webhook) when the status changes.

---

### R-T05 — Brute-Force Protection Not Implemented
**Probability**: HIGH | **Impact**: MEDIUM | **Severity**: 🟠 High | **Status**: ❌ Pending

**Description**: There is no automatic IP blocking mechanism for repeated authentication attempts. An attacker can try API key combinations without limit. Failed attempts are only logged.

**Detection indicator**: Log pattern: `security.invalid_api_key` repeated at high frequency from the same IP within a short period.

**Mitigation actions** (see Next Steps — Phase 1):
1. Implement `BruteForceProtectionService` with counters in Redis (atomic `INCR` / `EXPIRE`).
2. Integrate blocking in `ApiKeyAuthFilter` before key validation.
3. Return HTTP 429 with `Retry-After` header when the IP is blocked.
4. Configure an alert in the log aggregator when `security.invalid_api_key` > 10 events/min per IP.

---

### R-T06 — DNS Rebinding in Webhook URLs
**Probability**: HIGH | **Impact**: HIGH | **Severity**: 🔴 Critical | **Status**: ⚠️ Documented

**Description**: `SsrfGuard` validates the URL at request time but does not resolve DNS. An attacker registers `malicious.com → 1.2.3.4` (public IP), passes validation, then changes DNS to `169.254.169.254` (AWS metadata). When the HTTP client executes the webhook, it accesses the internal endpoint.

**Detection indicator**: Difficult to detect without network controls. Signal: unexpected outbound requests toward internal ranges captured by an egress firewall.

**Mitigation actions**:
1. In the HTTP client that executes webhooks, **re-validate the resolved IP** immediately before opening the TCP connection:
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
2. Implement **egress rules in firewall / Security Group** that block outbound traffic to RFC-1918 and `169.254.0.0/16`.
3. Configure **minimum DNS TTL** in the client (reject records with TTL < 60s as a rebinding indicator).
4. Use a **dedicated egress proxy** (e.g., Squid) with an allowlist of authorized domains for webhooks.

---

### R-T07 — MySQL Single Point of Failure
**Probability**: MEDIUM | **Impact**: HIGH | **Severity**: 🟠 High | **Status**: ❌ Unmitigated

**Description**: A single MySQL instance with no replication or automatic failover. A hardware failure, data corruption, or unplanned maintenance completely stops the service.

**Detection indicator**:
- Health: `GET /actuator/health` → `{"db": {"status": "DOWN"}}`
- Alert: `hikaricp_connections_active / hikaricp_connections_max > 0.9` (pool exhausted)

**Mitigation actions**:
1. Configure **AWS RDS Multi-AZ**: automatic failover to standby replica in < 60 seconds.
2. Enable **RDS automated backups** with 7-day retention + point-in-time recovery.
3. Add **read replica** for queries (`GetTransactionService`) and separate read/write pool in HikariCP.
4. Monitor active HikariCP connections: alert when `hikaricp_connections_active / hikaricp_connections_max > 0.9`.
5. Document and test the **manual failover runbook** before go-live.

---

## Security Risks

### R-S01 — API Key Leak or Compromise
**Probability**: HIGH | **Impact**: HIGH | **Severity**: 🔴 Critical | **Status**: ⚠️ Partial

**Description**: API keys are the only authentication barrier. If a key is leaked (commits, logs, Slack, shared Postman collections), an attacker can create fraudulent transactions or query customer data until the key is revoked.

**Detection indicator**:
- Traffic from unknown IPs using an existing key.
- `git log -S "test-key"` detects keys in history.
- Tools: **GitGuardian**, **TruffleHog**, **AWS Secrets Manager rotation alerts**.

**Mitigation actions**:
1. **Never** store keys in source code. Use `.gitignore` for `.env` files. Configure a **pre-commit hook** with TruffleHog.
2. Migrate to **AWS Secrets Manager** or **HashiCorp Vault** with automatic rotation (90 days maximum).
3. Enable **IP whitelist per API key** (`tumipay.orchestrator.security.ip-whitelist`) in production for all merchants.
4. Implement **automatic notification** when a key is used from a new IP (alert signal).
5. Define an **emergency revocation procedure** (< 5 minutes) documented in a runbook.
6. Use keys of at least **32 random bytes** (256 bits of entropy), not predictable strings.
7. Rotate keys for non-production environments (dev/staging) monthly.

---

### R-S02 — PII in Logs
**Probability**: LOW | **Impact**: HIGH | **Severity**: 🟠 High | **Status**: ✅ Mitigated (verify periodically)

**Description**: Customer personal data (email, document number, phone, name) could appear in logs if `log.debug(transaction.toString())` or similar is added. This violates Law 1581/2012 (Habeas Data) and can result in SIC sanctions.

**Detection indicator**: Periodic log search: `grep -E "[0-9]{10}|@[a-z]+\.[a-z]+" /var/log/app/*.log`

**Applied mitigation**:
- `@ToString(exclude = {"email", "documentNumber", "phoneNumber"})` in `Customer`.
- Filters log only request metadata (method, URI, correlation ID) — never the body.
- Logback configured to mask email and CC patterns with `MaskingPatternLayout`.

**Pending actions**:
1. Run a **quarterly log audit** to verify no PII fields appear.
2. Configure **Data Masking in the log aggregator** (ELK/Splunk) as a second line of defense.
3. Define a **log retention policy**: maximum 90 days for application logs, 1 year for audit logs (no PII).
4. Add an automated test verifying that `Customer.toString()` does not expose sensitive fields.

---

### R-S03 — Insider Threat
**Probability**: MEDIUM | **Impact**: HIGH | **Severity**: 🟠 High | **Status**: ❌ Unmitigated

**Description**: An employee with access to the production database, secrets, or code can exfiltrate customer data, modify transactions, or insert malicious code.

**Detection indicator**: Database accesses outside business hours, bulk `SELECT *` queries, direct modifications to production tables.

**Mitigation actions**:
1. **Principle of least privilege**: production database access only via audited tools (no direct SQL client); access requires approval and has a TTL.
2. Enable **MySQL Audit Plugin** or **AWS RDS Enhanced Monitoring** to record all queries.
3. Implement **environment separation**: developers do not have production access by default.
4. Enable **MFA** for AWS Console and secrets access.
5. Configure CloudTrail alerts for: secrets access at unusual times, bulk queries, IAM changes.
6. Apply **Breakglass Access** for emergencies: temporary access with dual approval and full audit trail.

---

### R-S04 — Dependency Compromise (Supply Chain)
**Probability**: MEDIUM | **Impact**: MEDIUM | **Severity**: 🟡 Medium | **Status**: ⚠️ Partial

**Description**: A malicious or compromised Maven dependency (`log4shell`, `xz-utils`) can affect the service. With ~80 direct and transitive dependencies, the attack surface is significant.

**Detection indicator**: CVE published for a library used in the project.

**Applied mitigation**:
- OWASP Dependency Check in the CI pipeline (`mvn dependency-check:check`).

**Pending actions**:
1. Enable **GitHub Dependabot** or **Snyk** for automatic CVE alerts on dependencies.
2. Use **Maven Enforcer Plugin** to prohibit versions with known CVEs (`<bannedDependencies>`).
3. Configure **Artifactory / Nexus** as a Maven proxy with artifact scanning before caching.
4. Pin exact versions of all dependencies (avoid ranges `[1.0,2.0)` in production).
5. Subscribe to security bulletins for Spring, MySQL Connector, and Resilience4j.

---

## Compliance Risks

### R-B01 — Regulatory Non-Compliance (SFC / Habeas Data / PCI DSS)
**Probability**: HIGH | **Impact**: HIGH | **Severity**: 🔴 Critical | **Status**: ⚠️ Partial

**Description**: As a payments platform in Colombia, TumiPay operates under:
- **Circular 052/2007 SFC**: security standards for electronic banking operations.
- **Law 1581/2012**: personal data protection (Habeas Data).
- **PCI DSS**: applies if credit card data is processed directly.
- **SAGRILAFT**: self-control system against money laundering.

**Detection indicator**: SFC inspection visit, formal user complaint, reported data breach.

**Mitigation actions**:
1. Hire **specialized legal counsel** in Colombian fintech before go-live.
2. Implement an **immutable audit log** of all operations (who, what, when) — `audit_events` table in a separate database.
3. Define and publish a **Privacy Policy** and **Privacy Notice** in accordance with Law 1581.
4. Implement a **consent revocation mechanism** (right to data erasure).
5. If processing cards directly: begin **PCI DSS SAQ-A or SAQ-D certification** process with a certified QSA.
6. Establish a **SAGRILAFT program**: ML/TF alert signals, restrictive lists (OFAC, UN, UIAF).

---

### R-B02 — Fraud and Money Laundering (AML/CFT)
**Probability**: HIGH | **Impact**: HIGH | **Severity**: 🔴 Critical | **Status**: ❌ Unmitigated

**Description**: The platform can be used to split large transactions (smurfing), create fictitious transactions between controlled accounts, or test stolen cards at a small scale (carding). Without AML controls, TumiPay is a passive accomplice.

**Detection indicator**: Unusual patterns: many small transactions from the same IP, same merchant with multiple distinct documents, provider rejections for invalid cards.

**Mitigation actions**:
1. Implement an **anti-fraud rules engine** (score per transaction):
   - Velocity: > 5 transactions per minute from the same merchant → alert.
   - Threshold: fractioned transactions summing to > UIAF reporting threshold (currently $10M COP).
   - Geolocation: request IP vs. country declared in the transaction.
2. Integrate with **restrictive lists** (OFAC SDN, UN lists, UIAF Colombia lists).
3. Implement **automatic suspicious activity reporting** (SAR) to the UIAF when AML patterns are detected.
4. Add a `risk_score` field to the transaction, calculated before sending to the provider.
5. Review with legal counsel the cash reporting threshold and unusual transaction thresholds.

---

### R-B03 — Double Charge to End User
**Probability**: HIGH | **Impact**: HIGH | **Severity**: 🔴 Critical | **Status**: ⚠️ Partial

**Description**: If there is a timeout in the provider call and the system retries (Resilience4j Retry), the provider may have processed the first attempt successfully while the retry creates a second charge. The end user sees two debits.

**Detection indicator**: User complaint, divergence between transactions in `APPROVED` state and transactions confirmed by the provider.

**Applied mitigation**:
- 3-layer idempotency (Redis + DB + @Version) prevents duplicates on the orchestrator side.
- Resilience4j Retry ignores `ValidationException` and `DuplicateTransactionException`.

**Pending actions**:
1. When calling the provider, **send `client_transaction_id` as the provider's idempotency key** (`Idempotency-Key` header in PSE/Nequi). If the provider supports it, this guarantees that the retry returns the result of the first attempt.
2. Before retrying, **query the transaction status at the provider** (`GET /provider/transaction/{id}`) to verify if it was already processed.
3. Implement **automated daily reconciliation**: compare all `APPROVED` transactions in the orchestrator against the provider's transaction report.
4. Define a **dispute resolution SLA** (< 24h for double charges) and a documented refund process.

---

## Operational Risks

### R-O01 — No Disaster Recovery Plan (DRP)
**Probability**: LOW | **Impact**: LOW | **Severity**: 🟢 Low | **Status**: ❌ Unmitigated

**Description**: There is no documented or tested DRP. In the event of total environment loss (AWS region down, accidental resource deletion), recovery time is unknown.

**Mitigation actions**:
1. Document **RTO** (Recovery Time Objective) and **RPO** (Recovery Point Objective) agreed with the business.
2. Configure **automated RDS backups** with monthly verified restoration.
3. Maintain **IaC (Terraform)** for the full environment to be able to recreate it in another region in < 2 hours.
4. Run a **semi-annual recovery drill** (controlled chaos engineering).

---

### R-O02 — Maximum Amount Misconfiguration
**Probability**: LOW | **Impact**: MEDIUM | **Severity**: 🟢 Low | **Status**: ✅ Mitigated

**Description**: If `MAX_AMOUNT_CENTS` is misconfigured (e.g., `0` or an extremely high value), all transactions can be blocked or anomalous amounts allowed.

**Applied mitigation**:
- Validation in `TransactionDomainValidator`: `amount > 0 AND amount ≤ maxAmountCents`.
- Safe default: `10,000,000,000` cents = $100,000,000 COP.

**Pending actions**:
1. Add validation on application startup (`@PostConstruct`) that verifies `maxAmountCents > 0`.
2. Implement an alert if `maxAmountCents` changes by more than 10x relative to the previous value (anomalous configuration change).

---

### R-O03 — Total Payment Provider Outage
**Probability**: MEDIUM | **Impact**: HIGH | **Severity**: 🟠 High | **Status**: ❌ Unmitigated

**Description**: If PSE (or the main provider) goes down, all transactions for that payment method fail. The Circuit Breaker opens, but no fallback provider is configured.

**Detection indicator**: Circuit Breaker in `OPEN` state for > 1 minute, error rate > 50%.

**Mitigation actions**:
1. Implement a **fallback provider** per payment method: if PSE fails, try a compatible alternative gateway.
2. Expose a **provider status endpoint** (`GET /api/v1/providers/health`) so the merchant can display available methods in their checkout.
3. Configure **automatic notification to the merchant** when their primary payment method becomes unavailable.
4. Subscribe to **each provider's status pages** (status.pse.com.co, etc.) and integrate with PagerDuty.

---

### R-O04 — Configuration Drift Between Environments
**Probability**: MEDIUM | **Impact**: MEDIUM | **Severity**: 🟡 Medium | **Status**: ❌ Unmitigated

**Description**: Over time, `dev`, `staging`, and `prod` configurations diverge. A security parameter (e.g., `API_KEYS` with test keys in prod) can mask vulnerabilities during development.

**Mitigation actions**:
1. Use **GitOps**: all configurations for all environments versioned in the same repository under `/config/{env}/`.
2. Implement a **Config Drift Detector**: script that compares configuration keys between environments and alerts if unexpected differences exist.
3. Review security configurations in the **pre-deploy checklist** for production.
4. Use **Spring Cloud Config Server** to centralize and audit configuration changes.

---

## Third-Party Risks

### R-P01 — Provider API Changes Without Notice
**Probability**: MEDIUM | **Impact**: HIGH | **Severity**: 🟠 High | **Status**: ❌ Unmitigated

**Description**: A provider updates their API (new version, required fields, authentication change) without sufficient notice. Adapters stop working in production.

**Detection indicator**: Sudden increase in `PaymentProviderException` errors in logs, Circuit Breaker in OPEN state.

**Mitigation actions**:
1. Establish a **formal change notification SLA contract** (minimum 30 days notice) with each provider.
2. Implement **contract tests** (Pact) between the adapter and the provider mock, executed in CI.
3. Maintain a **direct communication channel** with the technical team of each provider.
4. Version the integrations: `PsePaymentProviderAdapterV1`, `PsePaymentProviderAdapterV2` coexist during migration.
5. Automatically monitor the **provider health endpoint** and their changelogs / release notes.

---

### R-P02 — Webhook Delivery Failure to Merchant
**Probability**: MEDIUM | **Impact**: MEDIUM | **Severity**: 🟡 Medium | **Status**: ❌ Unmitigated

**Description**: When a transaction status changes (APPROVED/REJECTED), the orchestrator must notify the merchant via webhook. If the merchant's endpoint is down, the notification is lost and the merchant cannot release the order.

**Detection indicator**: HTTP 5xx responses to the merchant's `webhook_url`; transactions in a final state without confirmed notification.

**Mitigation actions**:
1. Implement the **Outbox Pattern**: record the notification event in the same database transaction that updates the status.
2. **Relay service with exponential retry**: 1s → 2s → 4s → 8s → 16s → 30s → 60s (maximum 10 attempts in 24h).
3. If all retries fail, **notify the merchant by email** as a fallback and mark the event as `EXHAUSTED`.
4. Expose a **status query endpoint** (`GET /api/v1/transactions/{id}`) so the merchant can poll as an alternative.
5. Record in `audit_events` each delivery attempt: timestamp, HTTP status, response, attempt number.

---

## Next Steps

This section maps the evolution path from the current MVP to a production-grade, horizontally scalable payment platform. Items are grouped by time horizon and labeled with priority.

### Legend

| Symbol | Meaning |
|---|---|
| 🔴 **Critical** | Blocking for go-live in production |
| 🟠 **High** | Required before horizontal scaling |
| 🟡 **Medium** | Significant improvement, plannable in sprints |
| 🟢 **Low** | Nice-to-have / controlled technical debt |

---

### Phase 1 — MVP → Production (Sprint 1-3)

These tasks must be completed **before the first production deployment**. Without them, the system has unacceptable functional or security gaps.

#### 🔴 1.1 Provider Webhook Receiver (`WebhookController`)

The highest risk in the current system (R-003). Transactions remain in `PROCESSING` state indefinitely because there is no endpoint to receive the asynchronous notification from the provider.

**What to implement:**
```
POST /api/v1/webhooks/{provider}
    │
    ├─ Verify provider HMAC signature (header: X-Webhook-Signature)
    ├─ Idempotency: ignore duplicates (same event_id already processed)
    ├─ UpdateTransactionStatusUseCase
    │    ├─ Load transaction by provider_transaction_id
    │    ├─ Validate state transition (PROCESSING → APPROVED/REJECTED)
    │    ├─ Persist with @Version (optimistic locking)
    │    └─ Emit domain event (notify merchant)
    └─ Respond 200 immediately (async processing)
```

**Expiration job** (complementary): batch that marks as `EXPIRED` those transactions stuck in `PROCESSING` past their `expiration_time`. Run every 5 minutes via `@Scheduled`.

---

#### 🔴 1.2 Real Provider Adapters

Replace `MockPaymentProvider` with real adapters. Each one implements `PaymentProviderPort` — no changes to the core.

| Provider | Type | Complexity | Priority |
|---|---|---|---|
| **PSE** (ACH Colombia) | Redirect flow | Medium | Sprint 1 |
| **Nequi** | Wallet push | High | Sprint 2 |
| **Bre-B** | Real-time rail | High | Sprint 2 |
| **Efecty** | Cash voucher | Medium | Sprint 3 |
| **Cards** (Redeban/Credibanco) | Card processing | Very high | Sprint 4 |

**Adapter structure:**
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

#### 🔴 1.3 Secrets Management (Secrets Manager)

API keys and database credentials **must not live in plain environment variables** in production.

**Migrate to:**
```
AWS Secrets Manager  →  Spring Cloud AWS Secrets Manager Starter
HashiCorp Vault      →  Spring Cloud Vault
```

**Concrete benefits:**
- Key rotation without redeployment
- Secrets access audit trail
- Encryption at rest and in transit
- RBAC per role/service

**API key rotation without downtime** (suggested design):
```yaml
# Instead of: api-keys: "key-A,key-B"
# Support simultaneous active versions during rotation:
api-keys:
  active:   ["key-new-2026"]
  retiring: ["key-old-2025"]   # Still accepted, but logs WARNING
  revoked:  []                 # Rejected with 401 + specific message
```

---

#### 🔴 1.4 Integration Tests with Testcontainers

Current tests use H2 in-memory, which diverges from MySQL in index behavior, collation, and Flyway. This is R-002.

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

**Minimum coverage for go-live:**
- `CreateTransactionService` end-to-end (happy path + idempotency)
- `ApiKeyAuthFilter` (IP whitelist + key validation)
- `CorrelationIdFilter` (correlation ID propagation in headers and MDC)
- Flyway migrations against real MySQL

---

#### 🔴 1.5 Brute-Force Protection (`BruteForceProtectionService`)

There is currently no protection against repeated authentication attempts. An attacker can try API keys indefinitely (R-T05). This control is blocking for production.

**What to implement:**
```java
// infrastructure/security/BruteForceProtectionService.java
@Service
public class BruteForceProtectionService {

    // Redis: atomic INCR + EXPIRE for distributed counters
    public boolean recordFailedAttempt(String ip) {
        String key = "bf:attempts:" + ip;
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts == 1) {
            redisTemplate.expire(key, attemptWindowMinutes, TimeUnit.MINUTES);
        }
        if (attempts >= maxAttempts) {
            redisTemplate.opsForValue().set("bf:blocked:" + ip, "1",
                lockoutMinutes, TimeUnit.MINUTES);
            return true; // IP now blocked
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

**Integration in `ApiKeyAuthFilter`** (before key validation):
```java
// Step 0 — IP block (before consuming validation resources)
if (bruteForceProtection.isBlocked(clientIp)) {
    log.warn("security.auth_blocked_by_brute_force ip={}", clientIp);
    writeTooManyRequests(response, "Too many failed attempts. Try again later.", lockoutSeconds);
    return;
}
```

**Environment variables to add:**
| Variable | Default | Description |
|---|---|---|
| `BRUTE_FORCE_MAX_ATTEMPTS` | `10` | Failed attempts before blocking |
| `BRUTE_FORCE_WINDOW_MIN` | `5` | Observation window (minutes) |
| `BRUTE_FORCE_LOCKOUT_MIN` | `15` | Block duration (minutes) |

**Use Redis** from the start — this makes counters distributed and working correctly with multiple replicas in Kubernetes.

---

### Phase 2 — Horizontal Scaling (Sprint 4-6)

Once in production with a single instance, these tasks allow scaling to multiple replicas in Kubernetes without consistency loss in security controls.

#### 🟠 2.1 Rate Limiting (Phase 2)

The service currently has no rate limiting. For horizontal scaling it is recommended to implement it with **Bucket4j + Redis** so that counters are distributed across replicas:

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

#### 🟠 2.2 Kubernetes — HPA and Probe Configuration

**HorizontalPodAutoscaler** based on CPU:
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

**Liveness / Readiness / Startup probes** (already enabled in `application.yml`):
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

#### 🟠 2.3 Persistence — High Availability

| Component | Current | Production |
|---|---|---|
| MySQL | Single instance | AWS RDS Multi-AZ + Read Replica |
| Redis | Single instance | AWS ElastiCache (cluster mode, 3 shards) |
| Connections | HikariCP 20 max | Separate read / write pool |
| Backup | Manual | RDS automated backup + point-in-time recovery |

**Read replica for queries** (adjustment in `application.yml`):
```yaml
spring:
  datasource:
    # Write → primary
    url: ${DB_PRIMARY_URL}
  datasource-read:
    # Read → replica (GetTransactionService)
    url: ${DB_REPLICA_URL}
```

---

### Phase 3 — Security Hardening (Sprint 7-9)

#### 🟡 3.1 mTLS Between Internal Services

Add mutual TLS authentication for inter-service calls (orchestrator → provider adapters, orchestrator → webhook dispatcher).

```
Orchestrator ──[mTLS]──► PSE Adapter
             ──[mTLS]──► Nequi Adapter
             ──[mTLS]──► Webhook Dispatcher
```

**Suggested stack:** Istio service mesh + SPIFFE/SPIRE for automatic certificate rotation.

---

#### 🟡 3.2 DNS Rebinding Protection (R-006)

The current `SsrfGuard` validates the URL at request reception time but does not resolve DNS. An attacker can:
1. Register `evil.com` pointing to `1.2.3.4` (public) → passes validation.
2. Change DNS to point to `169.254.169.254` → the HTTP client calls the metadata service.

**Fix in the HTTP client that executes webhooks:**
```java
// WebClientConfig.java — validate IP post-DNS resolution
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

In front of the Load Balancer, block:
- SQL / NoSQL injection in headers
- XSS in free-text fields
- Geo-blocking by country (if applicable to the business)
- Known bot signatures

**Options:** AWS WAF v2 (recommended for AWS), Cloudflare WAF, NGINX ModSecurity.

---

#### 🟡 3.4 Audit Trail and Regulatory Traceability

For compliance with **Habeas Data (Law 1581/2012)**, **SFC**, and eventually **PCI DSS**:

```java
// AuditEventEntity.java — separate audit table
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {
    UUID id;
    String eventType;         // TRANSACTION_CREATED, STATUS_UPDATED, AUTH_FAILED
    String actorKey;          // API key (hashed)
    String resourceId;        // transaction_id
    String clientIp;
    Instant occurredAt;
    String payload;           // JSON diff of the change (no PII)
}
```

**Publish to Kafka / SQS** for consumption by a dedicated audit service — do not write to the same transactional database.

---

### Phase 4 — Product Evolution (Sprint 10+)

#### 🟢 4.1 Multi-Tenancy and Per-Client Configuration

```
API Key A (Merchant X) → limit $5,000,000 COP, PSE only, CO only
API Key B (Merchant Y) → limit $100,000,000 COP, PSE + Nequi, CO + MX
```

Migrate static configuration from `application.yml` to a `client_configurations` table with Redis cache.

---

#### 🟢 4.2 Event Sourcing for Transaction State

Instead of overwriting the state (`PENDING → PROCESSING → APPROVED`), record each transition as an immutable event:

```
TransactionCreatedEvent
TransactionRoutedEvent
TransactionApprovedEvent
TransactionRefundedEvent
```

**Benefits:** complete audit trail, replay for debugging, compatible with CQRS for reporting projections.

---

#### 🟢 4.3 Merchant Notifications (Outbox Pattern)

To prevent notification loss if the webhook to the merchant fails:

```
┌─────────────────────────────────────────────┐
│  @Transactional                             │
│  1. UPDATE transaction SET status=APPROVED  │
│  2. INSERT INTO outbox_events (payload)     │  ← same transaction
└─────────────────────────────────────────────┘
         │
         ▼ (Polling job every 1s)
    Outbox Relay Service
         │
         ▼
    POST merchant_webhook_url   (with exponential retry)
```

---

#### 🟢 4.4 Advanced Monitoring and SLAs

| Alert | Condition | Action |
|---|---|---|
| Circuit breaker OPEN | `cb_state == "OPEN"` | Immediate alert |
| PROCESSING transactions > 10 min | Periodic query | Expiration job |
| `security.invalid_api_key` > 10/min per IP | Log pattern in aggregator | Alert + manual block until R-T05 is implemented |

---

### Roadmap Visual

```
Today (MVP)
    │
    ▼─────────────────── Sprint 1-3 ──────────────────────▶
    │  🔴 Webhook receiver + expiration job
    │  🔴 Real PSE adapter
    │  🔴 Secrets Manager (Vault / AWS SM)
    │  🔴 Integration tests (Testcontainers + MySQL)
    │  🔴 Brute-force protection (BruteForceProtectionService + Redis)
    │
    ▼─────────────────── Sprint 4-6 ──────────────────────▶
    │  🟠 Rate limiting → distributed Redis (Bucket4j Redis)
    │  🟠 Kubernetes HPA + probes tuning
    │  🟠 MySQL Multi-AZ + Redis ElastiCache cluster
    │  🟠 Nequi and Bre-B adapters
    │
    ▼─────────────────── Sprint 7-9 ──────────────────────▶
    │  🟡 Inter-service mTLS (Istio / SPIFFE)
    │  🟡 DNS rebinding fix in webhook HTTP client
    │  🟡 WAF in front of Load Balancer
    │  🟡 Audit table + Kafka → regulatory service
    │
    ▼─────────────────── Sprint 10+ ───────────────────────▶
       🟢 Multi-tenancy with per-client config in database
       🟢 Event Sourcing for transaction state
       🟢 Outbox Pattern for guaranteed notifications
       🟢 Grafana Dashboards
       🟢 PCI DSS SAQ-A assessment
```
