-- =============================================================================
-- TumiPay Transaction Orchestrator - Initial Schema
-- Version: 1.0.0
-- Description: Normalized relational schema for transaction orchestration
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Table: document_types
-- Description: Catalogue of legal document types per country
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document_types (
    code        VARCHAR(10)     NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(255)    NULL,
    country     CHAR(2)         NOT NULL COMMENT 'ISO 3166-1 Alpha-2',
    active      TINYINT(1)      NOT NULL DEFAULT 1,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_document_types PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Catalogue of legal document types';

-- -----------------------------------------------------------------------------
-- Table: payment_methods
-- Description: Catalogue of available payment methods (adapters)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_methods (
    id          VARCHAR(50)     NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    provider    VARCHAR(100)    NOT NULL COMMENT 'Adapter/Provider identifier',
    active      TINYINT(1)      NOT NULL DEFAULT 1,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)     NULL,
    CONSTRAINT pk_payment_methods PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Catalogue of payment methods and their provider adapters';

-- -----------------------------------------------------------------------------
-- Table: transaction_statuses
-- Description: Catalogue of transaction lifecycle statuses
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_statuses (
    code        VARCHAR(20)     NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(255)    NULL,
    is_final    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'Indicates if this is a terminal status',
    CONSTRAINT pk_transaction_statuses PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Transaction lifecycle status catalogue';

-- -----------------------------------------------------------------------------
-- Table: customers
-- Description: Customer information associated to transactions
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customers (
    id                  CHAR(36)        NOT NULL COMMENT 'UUID v4',
    document_type_code  VARCHAR(10)     NULL,
    document_number     VARCHAR(50)     NULL,
    country_calling_code VARCHAR(10)    NULL COMMENT 'E.g: +57',
    phone_number        VARCHAR(20)     NULL,
    email               VARCHAR(254)    NULL,
    first_name          VARCHAR(100)    NOT NULL,
    middle_name         VARCHAR(100)    NULL,
    last_name           VARCHAR(100)    NOT NULL,
    second_last_name    VARCHAR(100)    NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT fk_customers_document_type FOREIGN KEY (document_type_code)
        REFERENCES document_types(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Customer information per transaction';

CREATE INDEX idx_customers_email ON customers(email);
CREATE INDEX idx_customers_document ON customers(document_type_code, document_number);

-- -----------------------------------------------------------------------------
-- Table: transactions
-- Description: Core transaction table - main orchestrator entity
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transactions (
    id                      CHAR(36)        NOT NULL COMMENT 'UUID v4 - assigned by orchestrator',
    client_transaction_id   VARCHAR(100)    NOT NULL COMMENT 'Unique ID from client system',
    amount                  BIGINT          NOT NULL COMMENT 'Amount in cents, no decimal separator',
    currency_code           CHAR(3)         NOT NULL COMMENT 'ISO 4217',
    country_code            CHAR(2)         NOT NULL COMMENT 'ISO 3166-1 Alpha-2',
    payment_method_id       VARCHAR(50)     NOT NULL,
    webhook_url             VARCHAR(2048)   NOT NULL,
    redirect_url            VARCHAR(2048)   NOT NULL,
    description             VARCHAR(500)    NULL,
    expiration_time         DATETIME(6)     NULL,
    status_code             VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    customer_id             CHAR(36)        NOT NULL,
    processed_at            DATETIME(6)     NOT NULL,
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT uq_transactions_client_id UNIQUE (client_transaction_id),
    CONSTRAINT fk_transactions_payment_method FOREIGN KEY (payment_method_id)
        REFERENCES payment_methods(id),
    CONSTRAINT fk_transactions_status FOREIGN KEY (status_code)
        REFERENCES transaction_statuses(code),
    CONSTRAINT fk_transactions_customer FOREIGN KEY (customer_id)
        REFERENCES customers(id),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Core transaction records managed by the orchestrator';

CREATE INDEX idx_transactions_status ON transactions(status_code);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);
CREATE INDEX idx_transactions_currency ON transactions(currency_code);
CREATE INDEX idx_transactions_country ON transactions(country_code);

-- -----------------------------------------------------------------------------
-- Table: transaction_provider_responses
-- Description: Audit log of provider adapter responses per transaction
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_provider_responses (
    id              CHAR(36)        NOT NULL COMMENT 'UUID v4',
    transaction_id  CHAR(36)        NOT NULL,
    provider        VARCHAR(100)    NOT NULL,
    request_payload TEXT            NULL COMMENT 'Sanitized request sent to provider',
    response_code   VARCHAR(20)     NULL,
    response_message VARCHAR(500)   NULL,
    http_status     INT             NULL,
    responded_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_provider_responses PRIMARY KEY (id),
    CONSTRAINT fk_provider_responses_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Audit log of all provider adapter interactions';

CREATE INDEX idx_provider_responses_transaction ON transaction_provider_responses(transaction_id);
