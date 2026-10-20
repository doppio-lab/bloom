-- The day a sequence step is due. A follow-up is drafted one business day before its slot so it can be
-- reviewed; once approved it must still wait for that day instead of leaving in the current window.
ALTER TABLE drafts ADD COLUMN send_after TIMESTAMPTZ;
