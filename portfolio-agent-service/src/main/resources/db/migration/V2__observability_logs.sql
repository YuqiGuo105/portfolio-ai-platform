-- V2__observability_logs.sql
-- Model calls, tool calls, retrieval, safety events

create table model_call_log (
    id uuid primary key default gen_random_uuid(),
    run_id uuid references agent_run(id),
    model text not null,
    operation text not null,
    input_tokens integer,
    output_tokens integer,
    latency_ms integer,
    status text not null,
    created_at timestamptz not null default now()
);

create index idx_model_call_run on model_call_log(run_id);
create index idx_model_call_time on model_call_log(created_at desc);

create table tool_call_log (
    id uuid primary key default gen_random_uuid(),
    run_id uuid references agent_run(id),
    tool_name text not null,
    actor_user_id text,
    risk_level text,
    status text not null,
    input_json jsonb not null default '{}',
    output_json jsonb not null default '{}',
    latency_ms integer,
    created_at timestamptz not null default now()
);

create index idx_tool_call_run on tool_call_log(run_id);

create table retrieval_log (
    id uuid primary key default gen_random_uuid(),
    run_id uuid references agent_run(id),
    query_hash text,
    top_k integer,
    keyword_hits integer,
    vector_hits integer,
    returned_chunks integer,
    zero_hit boolean not null default false,
    latency_ms integer,
    created_at timestamptz not null default now()
);

create table safety_event (
    id uuid primary key default gen_random_uuid(),
    run_id uuid references agent_run(id),
    check_type text not null,
    verdict text not null,
    reason text,
    created_at timestamptz not null default now()
);

create table handoff_ticket (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references conversation(id),
    run_id uuid references agent_run(id),
    user_id text,
    reason text not null,
    summary text,
    external_ticket_id text,
    status text not null default 'created',
    created_at timestamptz not null default now()
);
