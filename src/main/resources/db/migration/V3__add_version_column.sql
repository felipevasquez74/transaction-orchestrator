ALTER TABLE transactions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic locking version - prevents concurrent update conflicts';
