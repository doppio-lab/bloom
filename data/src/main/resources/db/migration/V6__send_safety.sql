-- Sending safety. A draft is claimed (SENDING, Message-ID chosen) in its own transaction before SMTP is
-- called, so a crash after the send can never send it again. A draft that keeps failing ends as FAILED
-- instead of blocking the queue.
ALTER TABLE drafts
    ADD COLUMN send_attempts   INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_error      TEXT;

-- One open draft per sequence step. Older duplicates left by concurrent planning are rejected first.
UPDATE drafts d SET status = 'REJECTED', decided_at = now()
WHERE d.status IN ('PENDING_REVIEW', 'APPROVED')
  AND EXISTS (
      SELECT 1 FROM drafts o
      WHERE o.enrollment_id = d.enrollment_id AND o.step_index = d.step_index
        AND o.status IN ('PENDING_REVIEW', 'APPROVED') AND o.id > d.id
  );
CREATE UNIQUE INDEX drafts_one_open_per_step ON drafts (enrollment_id, step_index)
    WHERE status IN ('PENDING_REVIEW', 'APPROVED', 'SENDING');

-- Lookups done on every send and every reply
CREATE INDEX drafts_message_id_idx ON drafts (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX drafts_mailbox_sent_idx ON drafts (mailbox, sent_at);
