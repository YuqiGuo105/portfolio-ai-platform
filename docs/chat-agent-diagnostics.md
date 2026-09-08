# Chat Agent Diagnostics

These read-only tools are exposed on the authenticated `/mcp/admin` endpoint
for ADMIN users. They are not part of the public portfolio MCP endpoint.

## Login And Authorization

Connect the client to `/mcp/admin`, complete the existing OAuth/Supabase login,
and use an account whose current managed role is `ADMIN`. Missing or expired
credentials receive HTTP 401 with OAuth discovery; signed-in non-admin users
cannot discover or invoke these tools. The MCP endpoint rechecks managed roles
on every HTTP request; revocation and role demotion are not overridden by a
previous session or user-editable metadata. Authorization-service failures deny
the request rather than falling back to public access.

The internal gateway independently requires its service credential and the
authenticated caller's ADMIN role. Its agent adapter sends the agent's Bearer
credential; this is a server-to-server secret, never a browser credential.
Tool invocations go through the existing gateway audit path. These tools are
read-only: they do not change models, algorithms, prompts, or production config.
Use the recorded evidence to propose a change, test it, then approve deployment.
They report execution metadata and brief decision explanations, not hidden
chain-of-thought. Structured hidden-reasoning fields are redacted if present.

1. Call `agent.search_runs` with `q` (question text, session ID, conversation ID,
   or run ID), `hours` (1-720), and `limit` (1-100).
2. Call `agent.get_run_diagnostics` with the selected `runId`. The chat's
   expandable **Run details** also provides this identifier on new runs.
3. Inspect routing, the effective retrieval query, chunk/source IDs, titles,
   URLs, scores, model/prompt versions, timings, safety results, and final answer.
4. Use `admin.get_operation_timeline` with the same run ID for downstream
   OpenSearch delivery. That projection is eventually consistent; the agent
   diagnostics endpoint reads the durable database directly.

## Storage And Limits

The event recorder writes execution events to `outbox_event`; the publisher
projects them to OpenSearch. Exact run lookups use the existing
`idx_outbox_run_id_created_at` index. A response contains at most 500 events;
overflow, malformed records, absent start/terminal records, missing retrieval
provenance, fragmented legacy traces, and unavailable safety checks are explicit
diagnostic signals. In-progress runs can legitimately lack a terminal event.

New runs use a stable run-derived trace fallback when no explicit trace is set.
Only bounded retrieval metadata is added, not another copy of complete documents
or hidden model reasoning. Structured credential fields are redacted in the
diagnostic response. The existing recorder is best-effort on database failures;
missing events are not proof that a step did not execute. Do not describe this
as guaranteed complete audit storage.

Historical events cannot reveal chunk IDs that were never recorded. A successful
retrieval or completed run is not an answer-quality verdict. Check the source
relationship: a place mentioned in a title or visitor location is not proof of
travel, and an employer mention is not proof of an interview.

## Privacy And Operations

The owner has approved portfolio knowledge, including login-gated life articles,
for grounded public answers. Source access is separate: original article bodies
still require login. Retrieval carries `sourceRequiresLogin`; public source cards
omit restricted excerpts server-side and display a login label. Do not include
visitor histories, career-vault records, credentials, or unreviewed chat answers.

Deploy the article API and source redaction before applying
`knowledge-answer-source-access.sql` and backfilling approved life articles.
Never expose the raw knowledge RPC to browser roles to implement public answers.

No diagnostic operation reruns generation, sends email, or modifies content.
After deploying the agent endpoint and gateway catalog, reconnect the admin MCP
session to discover the tools. The gateway requires its existing
`domain.agent.base-url` and `domain.agent.internal-token` configuration.
