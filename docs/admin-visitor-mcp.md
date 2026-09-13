# Administrator Visitor Records

The authenticated `/mcp/admin` endpoint exposes two ADMIN-only read tools:

| Tool | Purpose |
| --- | --- |
| `visitor.search_events` | Filter and page through individual visit records. |
| `visitor.get_session_events` | Read events for an exact session ID within a bounded window. |

Both tools add bounded, ADMIN-only automation diagnostics at the MCP edge.

```json
{
  "filter": { "country": "US", "city": "Dallas", "event": "page_view" },
  "window": { "hours": 24 },
  "page": { "number": 0, "size": 25 }
}
```

Results preserve the existing Admin Visitors API response: `items`, `summary`,
`page`, `from`, and `to`. Items include event and receipt timestamps, page and
target URLs, referrer, approximate network location, client information, IP,
session/anonymous IDs, and event properties. Location is not a street address
or evidence of a visitor's identity. These records are private admin data.

Queries default to 24 hours and 25 records; the maximum is 31 days and 100
records per call. Use the returned `from`/`to` in `window` when requesting later
pages so the time boundary does not move. `from` is inclusive; `to` is exclusive.
Events are newest first. Empty results mean no matches in that window, not that
a person never visited. No automatic full-history export or batch fan-out runs.
Admin traffic is excluded by default; search supports `filter.includeAdmin`.

## Automation Evidence

Each item includes `automationEvidence`: a User-Agent pattern verdict, matched
patterns, whether that result conflicts with the stored `bot` flag, and an
explicit `operatorVerified: false`. The edge uses the pinned `isbot` detector;
it never classifies visitors by city or IP address. Stored records are unchanged.

`trafficAnalysis` summarizes only the returned page: detector version, event
counts, session observations, completeness, limitations, and the next action.
It separates `AUTOMATION_INDICATED` from `UNDETERMINED`; neither `bot=false` nor
an unmatched User-Agent proves a human. A mixed page remains mixed. IP diversity,
read progress, and reported engagement are observations, not identity checks.

Inspect `completeWindow` before generalizing. Session counts are not people
counts. Preserve `from`/`to` while paging, and do not add window-wide totals from
multiple pages. User-Agent strings can be spoofed; network ownership and bot
challenges are not verified. These diagnostics make no external enrichment or
LLM calls, perform no automatic batch fan-out, and add no usage-based API cost.

The aggregator fix recognizes crawler product tokens embedded in browser-like
User-Agents for future ingestion. Historical flags require a separate, reviewed
backfill; the edge's computed evidence works without rewriting history. Segment
preview also normalizes event names and reads only five-minute rollups, avoiding
double counting daily and five-minute data. Preview includes bot traffic and
reports bucket counts, not exact event-time counts or confirmed human visitors.

## Security Boundary

1. The MCP edge authenticates the user and resolves the managed role server-side.
2. Only ADMIN sees these tools in the protected catalog. The public `/mcp`
   catalog does not register them.
3. The gateway requires its internal service bearer and ADMIN role, replaces
   caller-supplied role context, and validates nested query objects.
4. A separate `visitor-admin` adapter permits only GET `/api/admin/visitors`
   and attaches `ANALYTICS_INTERNAL_TOKEN` as `X-Internal-Token`.
5. Missing service credentials fail closed. Responses are not stored in the
   write operation ledger; successful gateway responses and admin MCP transport
   use `Cache-Control: no-store`. Audit logs record metadata, not record bodies.

`analytics.*` tools keep the existing aggregate-only policy for every role:
confirmed minimum seven-day windows, no visitor-specific query fields, stripped
identifiers, and suppressed small buckets. Administrator detail access does not
change database grants, public endpoints, or ordinary-user privileges.

## Configuration

The gateway and aggregator must point to the same analytics environment.
`ANALYTICS_SERVICE_BASE_URL` selects the aggregator; `ANALYTICS_INTERNAL_TOKEN`
must match its `ANALYTICS_ADMIN_TOKEN`. Deployment maps both to the existing
`INTERNAL_API_TOKEN` Secret Manager secret. No token is embedded in source or
returned to the MCP client. Existing timeout, circuit-breaker, bulkhead, rate
limit, and bounded read retry behavior is reused.

After deployment, refresh the client's tool list (gateway catalog TTL: 60s).
Clients must invoke the exact discovered tool identifier, not a guessed display
label. New tools are unavailable on the old production revision.

## Verification

Verified locally on 2026-09-12: gateway tests 89/89, MCP edge tests 53/53,
and gateway packaging and edge configuration checks passed. A read-only
integration run connected the modified local MCP edge and gateway to the
production analytics API. Eight checks covered authenticated detail retrieval,
exact-session filtering, public catalog isolation, rejected missing/forged/low
privileges, query bounds, downstream credentials, identifier-free logs, and real
Dallas records with computed automation evidence. This validates local MCP and
gateway changes against production reads, not a deployed OAuth-client upgrade.
No production records were written and no deployment was performed.
