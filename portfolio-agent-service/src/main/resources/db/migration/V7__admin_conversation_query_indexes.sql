-- Supports protected admin conversation queries over the durable outbox history.
create index if not exists idx_outbox_created_at
    on outbox_event (created_at desc);

create index if not exists idx_outbox_event_type_created_at
    on outbox_event (event_type, created_at desc);

create index if not exists idx_outbox_run_id_created_at
    on outbox_event ((payload_json ->> 'runId'), created_at desc)
    where coalesce(payload_json ->> 'runId', '') <> '';
