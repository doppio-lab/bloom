-- Blacklist: when an account was excluded, and names that must never become an account
ALTER TABLE accounts ADD COLUMN excluded_at TIMESTAMPTZ;
UPDATE accounts SET excluded_at = now() WHERE excluded_reason IS NOT NULL;

CREATE TABLE blocklist (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    slug       TEXT NOT NULL UNIQUE,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Legal officers published by the company registry: suggestions for the contact book, not contacts yet
ALTER TABLE accounts ADD COLUMN officers_checked_at TIMESTAMPTZ;

CREATE TABLE officers (
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    full_name  TEXT NOT NULL,
    role       TEXT NOT NULL,
    source_url TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, full_name, role)
);
