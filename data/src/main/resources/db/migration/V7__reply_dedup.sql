-- An inbound email is recorded once. IMAP marks it seen only after it was processed, so a failure
-- leaves it unread for the next pass, and this key stops a second record if marking it seen failed.
ALTER TABLE replies ADD COLUMN inbound_key TEXT;
CREATE UNIQUE INDEX replies_inbound_key_idx ON replies (inbound_key) WHERE inbound_key IS NOT NULL;
CREATE INDEX replies_open_idx ON replies (received_at DESC) WHERE handled = false;
