-- Hosted job board of an account (Greenhouse, Lever, Ashby), found once then read every night.
-- A row with a NULL provider means "looked, none found": the lookup is not repeated before checked_at ages out.
CREATE TABLE job_boards (
    account_id BIGINT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    provider   TEXT,
    token      TEXT,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
