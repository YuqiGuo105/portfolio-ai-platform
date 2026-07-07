-- V1__conversation_and_runs.sql
-- Aiven PG: conversation state + agent execution tracking

create table conversation (
    id uuid primary key default gen_random_uuid(),
    tenant_id text not null default 'public',
    user_id text,
    status text not null default 'active',
    channel text not null default 'api',
    locale text default 'en-US',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_conversation_user on conversation(user_id, created_at desc);

create table message (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references conversation(id),
    role text not null,
    content text not null,
    metadata_json jsonb not null default '{}',
    created_at timestamptz not null default now()
);

create index idx_message_conversation on message(conversation_id, created_at);

create table agent_run (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references conversation(id),
    user_message_id uuid not null references message(id),
    status text not null default 'running',
    intent text,
    confidence double precision,
    started_at timestamptz not null default now(),
    completed_at timestamptz
);

create index idx_agent_run_conversation on agent_run(conversation_id, started_at desc);

create table agent_step (
    id uuid primary key default gen_random_uuid(),
    run_id uuid not null references agent_run(id),
    step_type text not null,
    status text not null,
    input_json jsonb not null default '{}',
    output_json jsonb not null default '{}',
    latency_ms integer,
    created_at timestamptz not null default now()
);

create index idx_agent_step_run on agent_step(run_id, created_at);
