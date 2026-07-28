"""Standalone Outlook -> CRM ingest.

Reads mail from local desktop Outlook (via COM/MAPI, reusing the code from
`server.py`) and POSTs each message verbatim to the CRM's /api/ingest endpoint
per INGESTION.md. Idempotent — the CRM dedupes on Message-ID (or falls back to
a content hash when the Message-ID header isn't present).

Usage:
    py ingest.py                                # last 7 days, all stores, POST
    py ingest.py --days 30 --verbose            # more history, per-message log
    py ingest.py --dry-run                      # build payloads, don't POST
    py ingest.py --no-include-sent              # only the Inbox side
    py ingest.py --store-name "user@corp.com"   # one store only
    py ingest.py --claude-base-url https://llm.core.blackduck.com \\
                 --claude-key sk-...            # route extraction via LiteLLM

Exit codes:
    0 — all POSTs succeeded (or dry-run finished)
    2 — pywin32 not installed / not on Windows
    3 — CRM /api/ping failed (is the server up?)
    4 — completed but at least one message errored during POST
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from typing import Any

# Reuse the COM/MAPI code we already validated with --selftest.
from server import HAVE_WIN32, get_email, list_recent_emails

DEFAULT_BASE_URL = "http://localhost:8080"
POST_TIMEOUT_S = 60  # extraction runs async, but the POST itself should be fast


def ping(base_url: str) -> None:
    """INGESTION.md Step 0 — confirm the CRM is up before shipping anything."""
    with urllib.request.urlopen(f"{base_url}/api/ping", timeout=10) as resp:
        if resp.status != 200:
            raise RuntimeError(f"/api/ping returned {resp.status}")


def _claude_block(claude_key: str | None, claude_base_url: str | None) -> dict[str, str]:
    claude: dict[str, str] = {}
    if claude_key:
        claude["apiKey"] = claude_key
    if claude_base_url:
        claude["baseUrl"] = claude_base_url
    return claude


def post_ingest(
    base_url: str,
    content: str,
    external_id: str,
    subject: str,
    received: str,
    claude_key: str | None,
    claude_base_url: str | None,
) -> dict[str, Any]:
    """POST one email. Returns the parsed CRM response.

    On duplicate the CRM returns 200 with `duplicate: true` AND the existing
    document's `status` — that lets us spot error-state docs from a prior run
    and route them to /reprocess. On first ingest it returns 202 with
    `duplicate: false`. Both are success from our side.
    """
    body: dict[str, Any] = {
        "content": content,
        "sourceType": "outlook_email",
        "metadataJson": json.dumps({"subject": subject, "received": received}),
    }
    if external_id:
        body["externalId"] = external_id
    claude = _claude_block(claude_key, claude_base_url)
    if claude:
        body["claude"] = claude

    req = urllib.request.Request(
        f"{base_url}/api/ingest",
        method="POST",
        headers={"Content-Type": "application/json"},
        data=json.dumps(body).encode("utf-8"),
    )
    with urllib.request.urlopen(req, timeout=POST_TIMEOUT_S) as resp:
        payload_text = resp.read().decode("utf-8") or "{}"
        return {
            "status_code": resp.status,
            "body": json.loads(payload_text),
        }


def post_reprocess(
    base_url: str,
    document_id: int,
    claude_key: str | None,
    claude_base_url: str | None,
) -> dict[str, Any]:
    """POST /api/documents/{id}/reprocess. Body is a ClaudeConfig (may be empty)."""
    body = _claude_block(claude_key, claude_base_url)
    req = urllib.request.Request(
        f"{base_url}/api/documents/{document_id}/reprocess",
        method="POST",
        headers={"Content-Type": "application/json"},
        data=json.dumps(body).encode("utf-8"),
    )
    with urllib.request.urlopen(req, timeout=POST_TIMEOUT_S) as resp:
        payload_text = resp.read().decode("utf-8") or "{}"
        return {
            "status_code": resp.status,
            "body": json.loads(payload_text),
        }


def _short(s: str, n: int = 60) -> str:
    s = (s or "").strip()
    return (s[: n - 1] + "…") if len(s) > n else s


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--days", type=int, default=7,
                    help="Days of history to scan (default: 7).")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL,
                    help=f"CRM base URL (default: {DEFAULT_BASE_URL}).")
    ap.add_argument("--include-sent", action=argparse.BooleanOptionalAction, default=True,
                    help="Include Sent Items alongside Inbox (default: on).")
    ap.add_argument("--store-name", default=None,
                    help="Limit to one store's DisplayName (default: all stores).")
    ap.add_argument("--limit", type=int, default=None,
                    help="Cap the number of messages processed.")
    ap.add_argument("--dry-run", action="store_true",
                    help="Enumerate + build payloads but do not POST.")
    ap.add_argument("--claude-key", default=None,
                    help="Override the CRM's default Claude API key per request.")
    ap.add_argument("--claude-base-url", default=None,
                    help="Override the CRM's default Claude base URL per request "
                         "(e.g. https://llm.core.blackduck.com for LiteLLM).")
    ap.add_argument("--verbose", "-v", action="store_true",
                    help="Log each message individually, not just totals.")
    args = ap.parse_args()

    if not HAVE_WIN32:
        print("ERROR: pywin32 unavailable. This script needs Windows + desktop Outlook.",
              file=sys.stderr)
        return 2

    print(f"outlook -> crm ingest")
    print(f"  days={args.days}  include_sent={args.include_sent}  "
          f"store_name={args.store_name or '(all)'}  base_url={args.base_url}"
          f"{'  DRY-RUN' if args.dry_run else ''}")

    if not args.dry_run:
        try:
            ping(args.base_url)
            print(f"  /api/ping OK")
        except (urllib.error.URLError, RuntimeError, OSError) as exc:
            print(f"ERROR: {args.base_url}/api/ping failed: {exc!r}", file=sys.stderr)
            print("  Start the CRM first: java -jar target\\quarkus-app\\quarkus-run.jar",
                  file=sys.stderr)
            return 3

    # Enumerate.
    listing = list_recent_emails(
        days=args.days,
        include_sent=args.include_sent,
        limit=args.limit or 10_000,
        store_name=args.store_name,
    )
    print(f"  {len(listing)} messages in range")
    if not listing:
        return 0

    # Ship.
    n_staged = 0
    n_duplicate = 0
    n_reprocessed = 0
    n_error = 0
    n_dry = 0
    total = len(listing)
    started = time.time()

    for i, meta in enumerate(listing, 1):
        subject = meta.get("subject", "")
        received = meta.get("received", "")
        sender = meta.get("sender_email", "")

        try:
            payload = get_email(meta["entry_id"])
        except Exception as exc:  # COM read failure
            n_error += 1
            print(f"  [{i}/{total}] BUILD FAIL: {exc!r}  ({_short(subject)})",
                  file=sys.stderr)
            continue

        if args.dry_run:
            n_dry += 1
            print(f"  [{i}/{total}] DRY  {received} <{sender}> {_short(subject)!r} "
                  f"content={len(payload['content'])}b "
                  f"mid={_short(payload.get('message_id',''), 40) or '(none)'}")
            continue

        try:
            result = post_ingest(
                base_url=args.base_url,
                content=payload["content"],
                external_id=payload.get("message_id", ""),
                subject=subject,
                received=received,
                claude_key=args.claude_key,
                claude_base_url=args.claude_base_url,
            )
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            n_error += 1
            print(f"  [{i}/{total}] HTTP {exc.code}: {_short(body, 200)}  "
                  f"({_short(subject)})", file=sys.stderr)
            continue
        except (urllib.error.URLError, OSError) as exc:
            n_error += 1
            print(f"  [{i}/{total}] POST FAIL: {exc!r}  ({_short(subject)})",
                  file=sys.stderr)
            continue

        rb = result["body"]
        is_dup = bool(rb.get("duplicate", False))
        doc_id = rb.get("documentId")
        doc_status = rb.get("status")

        if is_dup:
            # A prior run's extraction may have failed (e.g. wrong Claude creds).
            # If this doc is stuck in error state, retry via /reprocess with the
            # creds the user supplied this time.
            if doc_status == "error" and doc_id is not None:
                try:
                    post_reprocess(
                        base_url=args.base_url,
                        document_id=int(doc_id),
                        claude_key=args.claude_key,
                        claude_base_url=args.claude_base_url,
                    )
                    n_reprocessed += 1
                    if args.verbose:
                        print(f"  [{i}/{total}] reproc doc#{doc_id} "
                              f"{received} {_short(subject)!r}")
                except urllib.error.HTTPError as exc:
                    body = exc.read().decode("utf-8", errors="replace")
                    n_error += 1
                    print(f"  [{i}/{total}] REPROC HTTP {exc.code}: "
                          f"{_short(body, 200)}  ({_short(subject)})",
                          file=sys.stderr)
                except (urllib.error.URLError, OSError) as exc:
                    n_error += 1
                    print(f"  [{i}/{total}] REPROC FAIL: {exc!r}  "
                          f"({_short(subject)})", file=sys.stderr)
            else:
                n_duplicate += 1
                if args.verbose:
                    print(f"  [{i}/{total}] dup   doc#{doc_id} "
                          f"status={doc_status} {received} {_short(subject)!r}")
        else:
            n_staged += 1
            if args.verbose:
                extras = ""
                if rb.get("supportActivity"):
                    extras = "  [SUPPORT CASE — see INGESTION.md Step 1 note]"
                print(f"  [{i}/{total}] doc#{doc_id} {received} "
                      f"{_short(subject)!r}{extras}")

    elapsed = time.time() - started
    print()
    if args.dry_run:
        print(f"  totals (dry-run): {n_dry} would be POSTed, "
              f"{n_error} error  ({elapsed:.1f}s)")
        print(f"  re-run without --dry-run to actually ingest.")
    else:
        print(f"  totals: {n_staged} staged, {n_duplicate} duplicate, "
              f"{n_reprocessed} reprocessed, {n_error} error  ({elapsed:.1f}s)")
        if n_staged or n_reprocessed:
            print(f"  extraction runs in the background (~10-60s per document). "
                  f"Check status at {args.base_url}/ or GET /api/documents/{{id}}")
    return 0 if n_error == 0 else 4


if __name__ == "__main__":
    sys.exit(main())
