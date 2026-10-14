-- Scheduled jobs remember their last run in the database, not in memory: a restart neither runs a job
-- twice nor forgets it, and two instances never both run it. `period` is the day, week or month covered.
CREATE TABLE job_runs (
    name        TEXT PRIMARY KEY,
    period      DATE NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    error       TEXT
);
