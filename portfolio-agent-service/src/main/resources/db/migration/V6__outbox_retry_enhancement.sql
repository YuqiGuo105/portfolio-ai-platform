-- V6__outbox_retry_enhancement.sql
-- Add retry scheduling support to outbox_event for exponential backoff.
-- Add dead_letter status support.

ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

-- Index for efficient failed event polling with retry time
CREATE INDEX IF NOT EXISTS idx_outbox_retry 
    ON outbox_event(status, next_retry_at) 
    WHERE status = 'failed' AND next_retry_at IS NOT NULL;
