# Owner evidence repair

## Findings

- The raw KB contained the correct education record. The live legacy retrieval path did not select it.
- An earlier evidence audit quarantined 82 legacy Q&A chunks. Some are useful owner samples; others contain
  navigation fragments or generated refusals. Restoring all of them would reintroduce contamination.
- Current-source retrieval omitted profile and reviewed Q&A types. Travel article chunks had not been backfilled.
- The `Chat` table includes generated answers, so it must not be treated as a curated answer table.

## Changes

- Additive `match_current_knowledge_v2` RPC with version/source checks and explicitly reviewed `OWNER_QA`/`PROFILE`
  records. Approved copies retain the original content hash; editing or deleting the source invalidates them.
- Five reviewed samples/profile records plus missing canonical articles, projects and experience were indexed
  in one bounded, atomic insert: 30 chunks, 14 sources. Original quarantined rows were not rewritten or deleted.
- Answer authorization and source access remain separate. Login-gated originals have no public snippets;
  reviewed KB answers without a public source URL do not receive invented links.
- Prompt v4 treats previous assistant answers as unverified context, preserves sample-answer meaning and
  refuses to infer missing numerical facts. Input policy v5 permits retrieval of owner-approved biography.
- Query-vector caching is bounded to 256 entries / 10 minutes, keyed by model, dimension and text. It does
  not cache retrieved content or authorization results, and provider failures are not cached.
- Internal knowledge endpoints require a service token. Public error responses omit exception details.

## Verification and release status

- Focused local routing, grounding policy, knowledge eligibility, cache and internal-authentication tests pass.
- Supabase verified seven reviewed/profile/life-source probes are retrievable and no superseded hits return.
- RPC execution permissions: anon=false, authenticated=false, service_role=true.
- After explicit owner approval, `Portfolio/scripts/verify-owner-knowledge.mjs` passed all three live
  input-safety, Supabase retrieval and Gemini answer checks on 2026-09-08. Each query returned six hits.
  Background: 1148 ms retrieval / 4449 ms total; travel: 477 ms / 2219 ms; relationship: 374 ms / 1353 ms.
  Assertions verified Syracuse, all ten documented US cities, and the recorded single-status answer without
  a fabricated former-partner count. These are one-run component integration timings, not production
  browser latency or percentile measurements. The script uses supplied normalized queries rather than
  exercising the live route planner, and does not create conversations or send notifications.
- No production latency claim has been verified. The full application-context test stalled and was stopped;
  focused tests were run separately. No commit or service deployment has been performed for this repair.

## Deployment order

The additive SQL and data backfill are already applied. Deploy the agent's token-carrying KnowledgeClient
before enabling the authenticated knowledge service. Enable `KNOWLEDGE_SUPABASE_ENABLED=true`, set
`SUPABASE_URL`, server-only `SUPABASE_SERVICE_ROLE_KEY`, matching `KNOWLEDGE_INTERNAL_TOKEN`, and
`EMBEDDING_MODEL=gemini-embedding-001`. The workflow uses the existing agent service secret for the
internal credential. Do not publish the raw knowledge endpoint without this authentication.

After both services are ready, run the three live regressions and a browser Fast Mode check. Verify source
redaction, the precise university, the visited-city list, and no invented relationship count. Measure cold
and warm retrieval separately. README/architecture preview changes are unrelated and remain uncommitted.

## Follow-up reliability investigation

Production was inspected on 2026-09-08: agent revision `portfolio-agent-service-00120-8qj` still uses
image `sha-77985b4`. The fixes in this document are not deployed. The queried Supabase `agent_run`
and retrieval records returned no recent rows; `Chat` samples were older. This is not evidence that
recent production requests succeeded, and current runtime tracing still needs post-release verification.

Additional local corrections:

- Retrieval transport failures now report `unavailable`, not a zero-hit search, and do not prompt the
  standard owner-QA generator to invent a replacement answer. Valid empty searches remain distinguishable.
- Standard answer verification includes the retrieved evidence and parsed attachment context in the existing
  output-check call. Even a general-chat route cannot establish owner facts from an empty evidence set.
- A grounding warning allows one evidence-aware rewrite and one recheck. Unsupported, blocked or unchecked
  drafts are not published. General explanations remain allowed without attributing them to the owner.
- Draft tokens are buffered until verification. This trades early token display for preventing fabricated
  or blocked text from appearing before the final decision; progress events continue throughout.
- Provider failures propagate as errors, not strings presented as answers. Partial and empty answers are
  not accepted. Client-visible errors omit provider details, and failed model calls are recorded distinctly.
- Resolved uploaded-file content is supplied to generation and verification. Cancelling the generation
  stream disposes the upstream provider subscription; this cannot undo already-billed provider work.

Verification added:

- Pipeline regressions cover unavailable retrieval, partial generation, missing verification, failed rewrites,
  evidence-aware correction, parsed attachments, source-only research output, and generation cancellation.
- Provider-client tests cover HTTP 503/429, empty responses and valid text.
- Opt-in `SafetyServiceLiveGroundingTest` passed five synthetic Gemini checks: supported education PASS,
  contradictory education WARN, general arithmetic PASS, approved status PASS, invented partner count WARN.
  Individual classifier calls took 453-1060 ms in this run; no personal KB records were used.
- Five frontend SSE unit tests and ten mocked Chrome recovery cases (390 px / 1280 px) passed. They exercise
  EOF, error, final events, cancellation and subsequent input; they are not production end-to-end model tests.

Remaining limits: an LLM verifier is not a proof of truth; deep web-research claims are not checked against
the closed portfolio evidence set, and missing relevant retrieval still needs corpus-level evaluation.
Production deployment, browser-to-backend verification, trace inspection, and cold/warm latency sampling
remain release gates. Do not claim universal accuracy or production readiness from these focused checks.
