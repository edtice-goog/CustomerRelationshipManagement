# Ingestion Guide (for automation agents)

This document is written for an automation agent (e.g. Claude Cowork) whose job is to feed
communications into the Customer Success CRM. The full API contract is in [`openapi.yaml`](openapi.yaml)
(also served live at `/q/openapi`); this guide covers the workflow and the rules that matter.

## Base URL

```
http://localhost:8080
```

The API has no authentication yet and must only be reached from the local machine.

## Your job in one paragraph

Collect customer communications (emails, Teams chats) and POST them to the CRM. The CRM does all
interpretation itself — extraction of people, companies, contact details, sentiment, commitments,
support cases, and evaluations happens server-side from the raw text. Send content **verbatim and
complete** (headers and signatures included); never summarize, clean, or truncate it. Every endpoint
is idempotent, so re-running after a crash or overlap is always safe.

## Step 0 — health checks (once per run)

1. `GET /api/ping` → confirms the CRM is up.
2. `POST /api/claude/test` with body `{}` → confirms the CRM can reach the Claude API
   (free call, no tokens billed). If using a caller-supplied key (see Credentials), test with
   `{"apiKey": "sk-ant-..."}` instead.

If either fails, stop and report — do not queue content blindly.

## Step 1 — ingest emails

`POST /api/ingest` with `Content-Type: application/json`:

```json
{
  "content": "From: Jane Doe <jane@customer.com>\nSubject: ...\n\n<the ENTIRE raw email, verbatim, including the signature>",
  "sourceType": "outlook_email",
  "externalId": "<the email's Message-ID header if available>",
  "metadataJson": "{\"subject\":\"...\",\"received\":\"2026-07-28\"}"
}
```

- `content` (required): the whole email as plain text. Include From/To/Subject/Date headers when
  available — they improve extraction. Include the full signature block — it is the richest source.
- `externalId` (recommended): a stable id such as the RFC-5322 `Message-ID`. This is the dedup key.
  If omitted, a hash of the content is used, which also dedups exact re-sends.
- Response `202` → `{"documentId": N, "status": "staged", "duplicate": false, ...}`.
- Response `200` with `"duplicate": true` → already ingested; nothing to do. **This is success.**
- If the email belongs to a SalesForce support case (a `ref:...:ref` token anywhere in it), the
  response includes `supportActivity` with tracking info and `instructions` — follow those
  instructions (they ask you to find and submit the rest of the thread; duplicates are handled).

## Step 2 — ingest Teams conversations

`POST /api/ingest/teams` with a **conversation window** — a batch of consecutive messages from one
chat or channel, in chronological order. Do not send single messages; send enough of the
conversation to be meaningful (e.g. the last 24 hours of a chat, or a complete thread).

```json
{
  "chatName": "Jane Doe",
  "teamName": "",
  "channelName": "",
  "messages": [
    {
      "messageId": "<the Teams message id>",
      "sender": "Jane Doe",
      "senderEmail": "jane@customer.com",
      "timestamp": "2026-07-28 10:02",
      "text": "<message text, verbatim>"
    }
  ]
}
```

- Use `teamName` + `channelName` for channel conversations, `chatName` for 1:1/group chats.
- Always include `messageId` and `senderEmail` when available: message ids make overlapping
  windows dedup correctly (re-posting the same window returns `"duplicate": true`), and sender
  emails make entity resolution reliable.
- Same response shape as email ingestion.

## Step 3 — optionally verify processing

Extraction runs in the background and typically takes 10–60 seconds per document.
`GET /api/documents/{documentId}` → `status` becomes `extracted` (or `error`, with the reason in
`error`). `activityIds` lists any support cases or evaluations the document was linked to.
Polling is optional for bulk loading — errors are visible in the CRM's UI and documents can be
reprocessed later with `POST /api/documents/{id}/reprocess`.

## Credentials

By default the CRM uses its own configured Claude API key. To route extraction through a different
key (e.g. a corporate key with its own data-protection agreement), add to any ingest/reprocess body:

```json
"claude": { "apiKey": "sk-ant-...", "baseUrl": "https://api.anthropic.com" }
```

Keys are held in memory only for the duration of the extraction job and never persisted.

## Rules

1. **Verbatim content only.** Never summarize, redact, reformat, or truncate a communication
   before submitting. The CRM keeps raw content forever and re-extracts as its models improve;
   anything you drop is lost permanently.
2. **Idempotency is the safety net.** Submit generously; duplicates are detected and skipped.
   When in doubt whether something was already sent — send it again.
3. **Do not create entities, activities, or observations directly.** There are no endpoints for
   that by design: all CRM data derives from communications. Your only write paths are the two
   ingest endpoints (and reprocess).
4. **One communication = one submission.** Do not concatenate unrelated emails into one request.
   A Teams window is one conversation from one chat — never mix chats in one request.
5. **Report failures, don't retry forever.** A `502` from `/api/claude/test` or an `error` document
   status usually means an API-key or connectivity problem that a human needs to fix.

## Endpoint quick reference (read `openapi.yaml` for full schemas)

| Purpose | Endpoint |
|---|---|
| CRM alive? | `GET /api/ping` |
| Claude reachable? | `POST /api/claude/test` |
| Ingest email / raw text | `POST /api/ingest` |
| Ingest Teams window | `POST /api/ingest/teams` |
| Document status | `GET /api/documents/{id}` |
| Re-extract a document | `POST /api/documents/{id}/reprocess` |
| List activities (cases, evaluations) | `GET /api/activities` |
| Activity detail (status, commitments) | `GET /api/activities/{id}` |
