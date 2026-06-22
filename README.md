# portfolio-ai-platform

AI orchestration layer for the Portfolio site. Two Spring Boot 3.3 services
on Java 21:

| Service                    | Port | Role |
| -------------------------- | ---- | ---- |
| `portfolio-agent-service`  | 8090 | Receives natural-language chat, runs an **LLM-first intent classifier** (cheap OpenAI model), validates / resolves / authorizes, then dispatches one tool call through the MCP gateway. |
| `portfolio-mcp-gateway`    | 8091 | Loads a declarative tool catalog, enforces parameter / risk / idempotency rules, audits every call, and forwards into the domain services (`Portfolio` Next.js, `portfolio-admin-service`, `portfolio-notification-service`). |

## Architecture

```
User Input  (any language)
  │
  ▼
ChatWidget  ──▶  POST /api/chat  (SSE)         ──┐
                or POST /api/intent (JSON)       │
                                                 ▼
                           IntentOrchestrator (agent-service)
                                                 │
                              ┌─── classifier.classify()  ←─ OpenAI cheap model
                              │                       (env: OPENAI_INTENT_MODEL)
                              ▼
                           IntentValidator
                              │  reject | clarify | general-chat | execute
                              ▼
                           EntityResolver  ───▶  read-only gateway calls to
                              │                  resolve names → ids
                              ▼
                           PolicyGuard  (role / risk / confirmation gate)
                              │
                              ▼
                  staged PendingAction       OR    direct execute
                              │                        │
                              └─── user confirm ───────┘
                                                       ▼
                                               ToolExecutor
                                                       │
                                              POST /api/tools/{name}/invoke
                                                       ▼
                                          portfolio-mcp-gateway
                                          (catalog · validate · risk · idempotency · audit)
                                                       ▼
                       ┌────────────┬───────────────────┬────────────────┐
                       ▼            ▼                   ▼                ▼
                Portfolio /api    admin-service /api/admin/**     notification-service
                (search / contact / subscriptions)         (deliveries / subscribers / test send)
```

### Hard rules

1. **No regex intent routing.** The classifier is an LLM that returns
   strict JSON; the registry, validator, resolver and guard are all
   deterministic code.
2. The LLM only **classifies + extracts entities** — it never executes a
   tool, queries a DB, or assembles an event.
3. Every LLM output is **revalidated** server-side before any action.
4. The LLM may only choose tools from the in-code `ToolRegistry` allowlist.
5. The gateway **never writes Supabase tables directly** and **never
   constructs Kafka payloads**. Publish / reindex / retry forward to
   `portfolio-admin-service`, which owns event emission.
6. **Anonymous IDs are never invented.** Missing `sourceId`, `jobId`,
   `subscriberId`, `recipientId`, or `email` triggers `CLARIFICATION_NEEDED`
   or `EntityResolver`-driven read-only lookups.
7. Every non-`READ_ONLY` tool requires explicit confirmation. The agent
   stages a `PendingAction`; the user submits `pendingActionId` + `confirm`.
8. `RISKY_WRITE` and `DESTRUCTIVE` writes also support `dryRun: true` at
   the gateway level for previewing.
9. Every classification, validation, clarification, confirmation, and tool
   execution is **audited** (SLF4J JSON lines in Sprint 1; Supabase table
   in Sprint 2).

### Intent pipeline contracts

`POST /api/intent` (agent-service):

```json
{
  "sessionId": "abc-123",
  "userId": "u-42",
  "userEmail": "admin@example.com",
  "userRoles": "VIEWER,EDITOR,ADMIN",
  "utterance": "帮我看看 email 有没有失败",
  "pageContext": { "page": "/admin" }
}
```

Possible response `type`s:

| type                    | meaning                                           |
| ----------------------- | ------------------------------------------------- |
| `OK`                    | tool executed, `result` holds the response        |
| `ASK`                   | clarification needed; `message` + optional `options` |
| `CONFIRMATION_REQUIRED` | write staged; reply with `pendingActionId` + `confirm: true` to POST `/api/intent/confirm` |
| `FORBIDDEN`             | role lacked permission                            |
| `GENERAL_CHAT`          | small-talk / out of tool scope                    |
| `ERROR`                 | validation or upstream failure                    |

Confirmation leg (`POST /api/intent/confirm`):

```json
{ "sessionId": "abc-123", "pendingActionId": "uuid", "confirm": true }
```

## Tool surface (Sprint 1)

Declared once in [`portfolio-mcp-gateway/src/main/resources/tool-catalog.yaml`](portfolio-mcp-gateway/src/main/resources/tool-catalog.yaml)
and mirrored in [`portfolio-agent-service/.../ToolRegistry.java`](portfolio-agent-service/src/main/java/site/yuqi/agent/intent/ToolRegistry.java)
(the agent's static allowlist).

| Tool                                | Risk        | Target       |
| ----------------------------------- | ----------- | ------------ |
| `admin.search_content`              | READ_ONLY   | admin        |
| `admin.get_content`                 | READ_ONLY   | admin        |
| `admin.list_indexing_jobs`          | READ_ONLY   | admin        |
| `admin.list_outbox_events`          | READ_ONLY   | admin        |
| `admin.create_content_draft`        | SAFE_WRITE  | admin        |
| `admin.update_content`              | SAFE_WRITE  | admin        |
| `admin.publish_content`             | RISKY_WRITE | admin (Kafka owner) |
| `admin.reindex_rag`                 | RISKY_WRITE | admin (Kafka owner) |
| `admin.reindex_search`              | RISKY_WRITE | admin (Kafka owner) |
| `admin.retry_indexing_job`          | SAFE_WRITE  | admin        |
| `notification.get_delivery_stats`   | READ_ONLY   | notification |
| `notification.list_subscribers`     | READ_ONLY   | notification |
| `notification.get_subscriber`       | READ_ONLY   | notification |
| `notification.list_notifications`   | READ_ONLY   | notification |
| `notification.list_failed_deliveries` | READ_ONLY | notification |
| `notification.retry_failed_delivery`| SAFE_WRITE  | notification |
| `notification.send_test_notification` | SAFE_WRITE | notification |
| `notification.update_subscription`  | SAFE_WRITE  | notification |
| `notification.unsubscribe_subscriber` | DESTRUCTIVE | notification |

### Permission map (`PolicyGuard`)

| Tool                                  | Min role  |
| ------------------------------------- | --------- |
| `admin.search_content` `admin.get_content` `admin.list_indexing_jobs` `admin.list_outbox_events` | VIEWER |
| `admin.create_content_draft` `admin.update_content` | EDITOR |
| `admin.publish_content` `admin.reindex_rag` `admin.reindex_search` | PUBLISHER |
| `admin.retry_indexing_job`            | ADMIN     |
| `notification.get_delivery_stats` `notification.list_notifications` `notification.list_failed_deliveries` | VIEWER |
| All other `notification.*`            | ADMIN     |

Role inheritance: ADMIN ⟶ PUBLISHER ⟶ EDITOR ⟶ VIEWER.

### Confidence thresholds (`IntentValidator`)

| Risk         | Confidence policy                                              |
| ------------ | -------------------------------------------------------------- |
| READ_ONLY    | `≥0.85` execute · `0.65–0.85` clarify (optional escalation) · `<0.65` clarify |
| SAFE_WRITE   | always require confirmation; `<0.65` clarify                   |
| RISKY_WRITE  | always require confirmation; never auto-execute                |
| DESTRUCTIVE  | always require confirmation + ADMIN role                       |

Overrides via env: `MCP_INTENT_CONFIDENCE_READ_THRESHOLD`,
`MCP_INTENT_CONFIDENCE_CLARIFY_THRESHOLD`.

## Multilingual examples

Input `帮我找 Kafka 相关的文章` → `ADMIN_SEARCH_CONTENT`,
`admin.search_content`, `entities.keyword=Kafka`, `language=zh`, READ_ONLY,
executes if confidence ≥ 0.85.

Input `check if email notification failed` →
`NOTIFICATION_LIST_FAILED_DELIVERIES`, `notification.list_failed_deliveries`,
`entities={channel: EMAIL, status: FAILED}`, READ_ONLY.

Input `发布最新那篇 Kafka blog` → `CLARIFICATION_NEEDED`,
`admin.publish_content`, `missingEntities: ["sourceId"]`, RISKY_WRITE,
`clarificationQuestion: "你想发布哪一篇 Kafka blog？"` → `EntityResolver`
calls `admin.search_content` then presents options.

Input `重试那个失败的 indexing job` → `CLARIFICATION_NEEDED`,
`admin.retry_indexing_job`, `missingEntities: ["jobId"]` → `EntityResolver`
calls `admin.list_indexing_jobs(status=FAILED)` and picks the single match
or lists options.

Input `what's the weather today` → `GENERAL_CHAT` (out of scope).

## Audit table (Sprint 2 — Supabase)

Sprint 1 audits via SLF4J JSON. Sprint 2 should add this table and a
writer behind `AuditService`:

```sql
create table if not exists mcp_tool_audit_logs (
  id uuid primary key default gen_random_uuid(),
  actor text,
  tool_name text not null,
  intent text,
  input jsonb,
  output_summary jsonb,
  status text not null,
  error text,
  created_at timestamptz default now()
);
create index on mcp_tool_audit_logs (tool_name, created_at desc);
create index on mcp_tool_audit_logs (actor, created_at desc);
```

Redacted keys (never logged): `apiKey`, `api_key`, `authorization`,
`token`, `secret`, `password`, `serviceRoleKey`, `service_role_key`,
`openaiApiKey`, `openai_api_key`, `otp`.

## Local development

```bash
# build the multi-module workspace
mvn -B -DskipTests package

# run both services
docker compose up --build

# point ChatWidget (Portfolio) at the local agent:
#   NEXT_PUBLIC_AGENT_SERVICE_URL=http://localhost:8090
```

`portfolio-agent-service` exposes:

| Route                  | Description                       |
| ---------------------- | --------------------------------- |
| `POST /api/chat`       | SSE stream (ChatWidget)           |
| `POST /api/intent`     | JSON intent (testing / admin)     |
| `POST /api/intent/confirm` | Confirmation leg of a write   |
| `GET  /api/health`     | Liveness                          |

`portfolio-mcp-gateway` exposes (internal-only, bearer required when token configured):

| Route                            | Description                  |
| -------------------------------- | ---------------------------- |
| `POST /api/tools/{name}/invoke`  | Dispatch a single tool       |
| `GET  /api/tools`                | List the catalog             |
| `GET  /api/health`               | Liveness                     |

## Deployment

Two independent GitHub Actions workflows under `.github/workflows/` deploy
each service to Cloud Run (us-central1, project `portfolio-notify-prod`).
They follow the same WIF / Artifact Registry pattern as
`portfolio-admin-service`.

```bash
gh workflow run deploy-agent-service.yml --ref main
gh workflow run deploy-mcp-gateway.yml   --ref main
```

### GitHub repository variables expected

| Variable                       | Used by | Example value                                           |
| ------------------------------ | ------- | ------------------------------------------------------- |
| `GCP_PROJECT_ID`               | both    | `portfolio-notify-prod`                                 |
| `GCP_REGION`                   | both    | `us-central1`                                           |
| `ARTIFACT_REPO`                | both    | `portfolio`                                             |
| `WIF_PROVIDER`                 | both    | `projects/.../providers/...`                            |
| `DEPLOYER_SA_EMAIL`            | both    | `gh-deployer@portfolio-notify-prod.iam.gserviceaccount.com` |
| `AGENT_RUNTIME_SA_EMAIL`       | agent   | `agent-runtime@portfolio-notify-prod.iam.gserviceaccount.com` |
| `GATEWAY_RUNTIME_SA_EMAIL`     | gateway | `gateway-runtime@portfolio-notify-prod.iam.gserviceaccount.com` |
| `ALLOWED_ORIGINS`              | both    | `https://www.yuqi.site,https://yuqi.site`               |
| `MCP_GATEWAY_BASE_URL`         | agent   | `https://portfolio-mcp-gateway-XXXX-uc.a.run.app`       |
| `OPENAI_INTENT_MODEL`          | agent   | `gpt-4o-mini`                                           |
| `OPENAI_INTENT_ESCALATION_MODEL` | agent | `gpt-4o` (or leave blank to disable)                    |
| `PORTFOLIO_BASE_URL`           | gateway | `https://www.yuqi.site`                                 |
| `ADMIN_SERVICE_BASE_URL`       | gateway | `https://portfolio-admin-service-XXXX-uc.a.run.app`     |
| `NOTIFICATION_SERVICE_BASE_URL`| gateway | `https://portfolio-notification-service-XXXX-uc.a.run.app` |

### Google Secret Manager secrets expected

Create these as secrets (NOT as plain env vars) and grant the runtime
service accounts `Secret Manager Secret Accessor`:

| Secret                      | Used by | Notes                                |
| --------------------------- | ------- | ------------------------------------ |
| `OPENAI_API_KEY`            | agent   | cheap-model classifier credentials   |
| `MCP_GATEWAY_INTERNAL_TOKEN`| both    | agent → gateway bearer auth          |
| `NOTIFICATION_INTERNAL_TOKEN` | gateway | gateway → notification-service     |
| `SPRING_DATASOURCE_URL`     | gateway | Supabase JDBC URL (Flyway disabled)  |
| `SPRING_DATASOURCE_USERNAME`| gateway | shared with admin-service            |
| `SPRING_DATASOURCE_PASSWORD`| gateway | shared with admin-service            |
