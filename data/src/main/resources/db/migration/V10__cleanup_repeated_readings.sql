-- Cleanup of readings stored before the write rules changed (a state signal read again replaces the previous
-- readings, a fact read again refreshes its date). Conservative on purpose: it only removes rows that the
-- application itself already hides or replaces, and it is safe to run again (a second run deletes nothing).

-- 1. State signals: one row per signal, per job offer, per night piled up. Keep the latest reading of each
--    (account, type), the same key ExposedFactRepository.saveSignals replaces. A reading is every row written
--    within 6 hours of the latest one (the sources of one nightly run, each with its own clock). Event signals
--    (tender, funding, referral...) are never touched. Keep the type list in line with SignalType.isState.
DELETE FROM signals s
USING (
    SELECT id, observed_at, max(observed_at) OVER (PARTITION BY account_id, type) AS latest
    FROM signals
    WHERE type IN ('RATING_DROP', 'QUALITY_COMPLAINTS', 'RELEASE_STALL', 'HIRING_MOBILE', 'HIRING_KMP', 'HIRING_STUCK')
) r
WHERE s.id = r.id AND r.observed_at < r.latest - interval '6 hours';

-- 2. Facts: the same topic read again with a changed number ("published 12 days ago", "rated 4.3 over 1234
--    notes") got a new id at each read. Same topic = same account, kind, source URL and statement once its
--    digit runs are masked, exactly the latestPerTopic key (Kotlin \d+ is ASCII digits, so [0-9]+ here).
--    An older reading is removed only when
--      - a reading of the same topic exists that is at least 12 hours newer (two distinct facts read from one
--        page in the same run are seconds apart and are never merged), and
--      - nothing references its id: no draft (cited ids, body markers, subject, rationale, guard report),
--        no dossier (summary, hypotheses, angle) and no remaining signal (fact_ids).
--    ReviewQueue and DecideDraft keep the full list on purpose: a draft may cite an older reading.
DELETE FROM facts f
WHERE EXISTS (
        SELECT 1 FROM facts g
        WHERE g.account_id = f.account_id AND g.kind = f.kind AND g.source_url = f.source_url
          AND g.id <> f.id
          AND g.retrieved_at >= f.retrieved_at + interval '12 hours'
          AND regexp_replace(g.statement, '[0-9]+', '#', 'g') = regexp_replace(f.statement, '[0-9]+', '#', 'g')
    )
  AND NOT EXISTS (
        SELECT 1 FROM drafts d
        WHERE strpos(concat_ws(' ', d.cited_fact_ids, d.body, d.subject, d.rationale, d.guard_report, d.reference_used), f.id) > 0
    )
  AND NOT EXISTS (
        SELECT 1 FROM dossiers o
        WHERE strpos(concat_ws(' ', o.summary, o.hypotheses, o.angle), f.id) > 0
    )
  AND NOT EXISTS (
        SELECT 1 FROM signals s WHERE strpos(s.fact_ids, f.id) > 0
    );
