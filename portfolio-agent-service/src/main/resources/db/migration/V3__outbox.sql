-- V3__outbox.sql
-- Transactional outbox for async sync to OpenSearch log indexes.
-- Replaces Kafka for event publishing (free tier topic limit).

create table outbox_event (
    id uuid primary key default gen_random_uuid(),
    event_type text not null,
    payload_json jsonb not null,
    status text not null default 'pending',
    attempts integer not null default 0,
    created_at timestamptz not null default now(),
    published_at timestamptz
);

create index idx_outbox_pending on outbox_event(status, created_at)
    where status = 'pending';
