-- Stale-claim detection must use the claim time, not the original schedule time.
-- Otherwise an event that waited in the queue longer than the stale threshold is
-- reset to PENDING the moment it is claimed and gets published twice.
ALTER TABLE outbox_events ADD COLUMN claimed_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_processing_claimed
    ON outbox_events (claimed_at) WHERE status = 'PROCESSING';

-- Rows already PROCESSING when this version is deployed (claimed by an old relay)
-- get a claim time, so the stale-claim release can pick them up.
UPDATE outbox_events SET claimed_at = now() WHERE status = 'PROCESSING';
