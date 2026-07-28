# Customer Success CRM

A customer-focused CRM where the primary input is **agents reading existing communications**,
not humans typing into forms. Paste (or POST) an email; people, organizations, contact details,
sentiment, commitments, and support-case status are extracted automatically by Claude.

## Design principles

1. **Customer success over seller bookkeeping** — the system tracks how customers are doing
   (sentiment over time, commitments owed, support-case health), not contract fields.
2. **Observation-based data model** — there are no wide profile tables. Every fact is an
   *observation* with confidence, a verbatim evidence quote, and a link to the source document.
   A profile is a projection over active observations. New attribute types require no schema change.
3. **Agents as the input layer** — communications land verbatim in a staging area, then an
   extraction pipeline (Claude with typed structured outputs) turns them into observations.
   Raw content is kept forever, so extraction can be re-run as prompts and models improve.
4. **Detection is code, judgment is the model** — deterministic things (SalesForce case tokens,
   dedup hashing) are regex/SQL; interpretive things (who is mentioned, how the case is going)
   are model calls.

## What it does today

- **Ingestion**: web form paste, or `POST /api/ingest` (JSON). Content-hash dedup.
- **Extraction**: people, organizations, titles/phones/addresses from signatures, per-message
  sentiment, interaction summaries, and commitments ("I'll send X by Friday").
- **Promotion**: observations ≥ threshold confidence go live; the rest wait in a one-click
  review queue.
- **Support case tracking** (not ticket management): SalesForce `ref:...:ref` tokens are
  recognized at ingest; emails are mapped to cases; on every new case email the full history
  is re-assessed on three tracks — customer disposition, technical progress, and whether work
  is reaching the **root cause** — plus an overall green/yellow/red health.
- **Machine-facing API** with OpenAPI description (`/q/openapi`, Swagger UI at `/q/swagger-ui`)
  so agents (e.g. Copilot) can drive ingestion. Callers may supply their own Claude API key and
  endpoint per request (e.g. a corporate key with different data-protection terms); keys are
  held in memory only.
- **Data housekeeping with durable decision records**: candidate duplicate entities are found
  by code (name normalization + token matching) and judged by the model against full observation
  profiles. Every deliberation — merge *or* keep-separate — is stored as a housekeeping record
  linked to both entities, with a statement of evidence and reasoning. Settled pairs are only
  reconsidered when new evidence arrives, and the prior reasoning is given to the model when
  they are. Runs on a schedule, opportunistically when extraction creates a similar-named
  entity, and on demand (`POST /api/housekeeping/run`). High-confidence merges execute
  automatically (losers become alias tombstones via `merged_into`); borderline pairs wait for
  a one-click human decision that also becomes part of the record.

## Architecture

Quarkus 3 / Java 21, hexagonal:

- `domain/` — records: `Entity`, `Observation`, `SourceDocument`, `SupportCase`, ...
- `store/` — persistence ports; `store/jdbc/` implements them with portable SQL.
  Local dev uses SQLite (`data/crm.db`); production targets RDS Postgres by changing
  `crm.db.url` and adding a DDL variant in `Database.java`.
- `extract/` — Claude extraction via the Anthropic Java SDK with typed structured outputs.
- `ingest/`, `pipeline/`, `cases/` — staging, background extraction, case tracking.
- `web/` — JSON API (`ApiResource`) + server-rendered UI (`PagesResource`, Qute).

The eventual deployment target is AWS Lambda (Quarkus native image), which is why the core
stays framework-light and storage sits behind ports.

## Running locally

Requires JDK 21 and Maven, plus an Anthropic API key.

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."   # or set as a user environment variable
mvn quarkus:dev                          # dev mode, or:
mvn package -DskipTests && java -jar target\quarkus-app\quarkus-run.jar
```

Then open http://localhost:8080 (UI) or http://localhost:8080/q/swagger-ui (API).

Configuration lives in `src/main/resources/application.properties`
(`crm.db.url`, `crm.extraction.model`, `crm.autoPromoteThreshold`).

## API quick reference

```bash
# Liveness
curl http://localhost:8080/api/ping

# Verify Claude connectivity (free call) — optionally with your own key/endpoint
curl -X POST http://localhost:8080/api/claude/test -H "Content-Type: application/json" \
  -d '{"apiKey":"sk-ant-...","baseUrl":"https://api.anthropic.com"}'

# Ingest a communication
curl -X POST http://localhost:8080/api/ingest -H "Content-Type: application/json" \
  -d '{"content":"<raw email>","sourceType":"outlook_email","claude":{"apiKey":"sk-ant-..."}}'

# Poll extraction status / inspect cases
curl http://localhost:8080/api/documents/1
curl http://localhost:8080/api/cases
```

## Status / roadmap

Working v1 used daily by its author. No authentication yet (localhost only — auth is a
prerequisite for any non-local deployment).
