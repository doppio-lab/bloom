-- Découverte automatique de prospects

-- Une entreprise trouvée via une offre d'emploi n'a pas toujours de site identifié
ALTER TABLE accounts ALTER COLUMN domain DROP NOT NULL;

-- Clé de dédoublonnage : nom normalisé ("Vélomoov SAS" -> "velomoov"), calculée par l'application.
-- Le remplissage SQL ci-dessous ne reproduit pas CompanyNames.normalize (accents, formes juridiques) :
-- il supposait une table vide au moment de la migration. Tout slug écrit depuis vient de l'application.
ALTER TABLE accounts ADD COLUMN slug TEXT;
UPDATE accounts SET slug = lower(regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g')) WHERE slug IS NULL;
ALTER TABLE accounts ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX accounts_slug_uq ON accounts (slug);

ALTER TABLE accounts ADD COLUMN source TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE accounts ADD COLUMN excluded_reason TEXT;

-- Ce que la découverte a déjà examiné (offre d'emploi, app), pour ne pas refaire le travail chaque nuit
CREATE TABLE discovery_seen (
    key        TEXT PRIMARY KEY,   -- "ft:<id offre>" ou "app:<id app>"
    kept       BOOLEAN NOT NULL,
    reason     TEXT,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
