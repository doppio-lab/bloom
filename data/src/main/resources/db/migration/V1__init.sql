-- Doppio Bloom : schéma initial (PostgreSQL 16)

CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL,
    domain          TEXT NOT NULL UNIQUE,
    siren           TEXT,
    sector          TEXT NOT NULL DEFAULT 'OTHER',
    market          TEXT NOT NULL DEFAULT 'FR',
    headcount       INT,
    ios_app_id      BIGINT,
    android_package TEXT,
    warm_path       TEXT,
    owner_note      TEXT,
    score           INT NOT NULL DEFAULT 0,
    score_detail    TEXT NOT NULL DEFAULT '{}',
    recommended_offer TEXT,
    refreshed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE contacts (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    full_name    TEXT NOT NULL,
    role         TEXT NOT NULL,
    persona      TEXT NOT NULL,
    email        TEXT,
    linkedin_url TEXT,
    data_source  TEXT NOT NULL,
    language     TEXT NOT NULL DEFAULT 'fr',
    -- RGPD : date du dernier contact venant du prospect, base de la purge à 3 ans
    last_inbound_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX contacts_email_uq ON contacts (lower(email)) WHERE email IS NOT NULL;

CREATE TABLE facts (
    id           TEXT PRIMARY KEY,
    account_id   BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    kind         TEXT NOT NULL,
    statement    TEXT NOT NULL,
    source_url   TEXT NOT NULL,
    excerpt      TEXT,
    retrieved_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX facts_account_idx ON facts (account_id, retrieved_at DESC);

CREATE TABLE signals (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,
    strength    DOUBLE PRECISION NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    fact_ids    TEXT NOT NULL DEFAULT '[]'
);
CREATE INDEX signals_account_idx ON signals (account_id, observed_at DESC);

CREATE TABLE dossiers (
    account_id  BIGINT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    summary     TEXT NOT NULL,
    hypotheses  TEXT NOT NULL DEFAULT '[]',
    angle       TEXT NOT NULL,
    offer       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sequences (
    id     BIGSERIAL PRIMARY KEY,
    name   TEXT NOT NULL UNIQUE,
    offer  TEXT NOT NULL,
    steps  TEXT NOT NULL
);

CREATE TABLE enrollments (
    id             BIGSERIAL PRIMARY KEY,
    contact_id     BIGINT NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    sequence_id    BIGINT NOT NULL REFERENCES sequences(id),
    status         TEXT NOT NULL,
    next_step      INT NOT NULL DEFAULT 0,
    last_action_at TIMESTAMPTZ,
    enrolled_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    thread_message_id TEXT
);
CREATE UNIQUE INDEX enrollments_active_uq ON enrollments (contact_id) WHERE status IN ('ACTIVE', 'PAUSED');

CREATE TABLE drafts (
    id             BIGSERIAL PRIMARY KEY,
    enrollment_id  BIGINT NOT NULL REFERENCES enrollments(id) ON DELETE CASCADE,
    step_index     INT NOT NULL,
    channel        TEXT NOT NULL,
    subject        TEXT,
    body           TEXT NOT NULL,
    cited_fact_ids TEXT NOT NULL DEFAULT '[]',
    reference_used TEXT,
    rationale      TEXT NOT NULL,
    status         TEXT NOT NULL,
    guard_report   TEXT NOT NULL DEFAULT '[]',
    edited_by_human BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at     TIMESTAMPTZ,
    sent_at        TIMESTAMPTZ,
    message_id     TEXT,
    mailbox        TEXT
);
CREATE INDEX drafts_status_idx ON drafts (status, created_at);

CREATE TABLE replies (
    id            BIGSERIAL PRIMARY KEY,
    enrollment_id BIGINT REFERENCES enrollments(id) ON DELETE SET NULL,
    from_address  TEXT NOT NULL,
    subject       TEXT,
    body          TEXT NOT NULL,
    intent        TEXT NOT NULL,
    confidence    DOUBLE PRECISION NOT NULL,
    follow_up_on  DATE,
    summary       TEXT NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL,
    handled       BOOLEAN NOT NULL DEFAULT false
);

-- Liste d'opposition : jamais purgée (c'est la preuve qu'on respecte l'opposition)
CREATE TABLE suppressions (
    value      TEXT PRIMARY KEY, -- email en minuscules ou @domaine
    reason     TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE deals (
    id             BIGSERIAL PRIMARY KEY,
    account_id     BIGINT NOT NULL REFERENCES accounts(id),
    offer          TEXT NOT NULL,
    stage          TEXT NOT NULL,
    amount_eur     BIGINT NOT NULL DEFAULT 0,
    source_signal  TEXT,
    expected_close DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Observabilité des agents : coût et latence par exécution
CREATE TABLE agent_runs (
    id            BIGSERIAL PRIMARY KEY,
    agent         TEXT NOT NULL,
    account_id    BIGINT,
    model         TEXT NOT NULL,
    input_tokens  INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    duration_ms   BIGINT NOT NULL,
    success       BOOLEAN NOT NULL,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Journal d'événements (envoi, réponse, désinscription, validation) : source des métriques
CREATE TABLE events (
    id          BIGSERIAL PRIMARY KEY,
    type        TEXT NOT NULL,
    account_id  BIGINT,
    contact_id  BIGINT,
    draft_id    BIGINT,
    payload     TEXT NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX events_type_time_idx ON events (type, occurred_at DESC);
