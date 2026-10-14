-- Requests published by a buyer on a marketplace (Codeur.com): there is no named company to prospect,
-- the answer goes through the platform. Kept apart from accounts so no email sequence can start from them.
-- Only the title, the budget band, the categories and the link are stored, not the text of the request.
CREATE TABLE demands (
    key          TEXT PRIMARY KEY,   -- "codeur:<project id>"
    source       TEXT NOT NULL,
    title        TEXT NOT NULL,
    url          TEXT NOT NULL,
    budget       TEXT,
    categories   TEXT NOT NULL DEFAULT '[]',
    published_at TIMESTAMPTZ,
    dismissed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX demands_open_idx ON demands (published_at DESC) WHERE dismissed_at IS NULL;
