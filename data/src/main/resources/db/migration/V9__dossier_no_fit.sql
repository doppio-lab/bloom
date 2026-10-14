-- The researcher may conclude that no offer fits. Kept as such instead of falling back to the discovery
-- offer, so no sequence starts on its own for that account.
ALTER TABLE dossiers ADD COLUMN no_fit BOOLEAN NOT NULL DEFAULT false;
