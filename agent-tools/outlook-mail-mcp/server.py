"""
outlook-mail MCP server — read-only access to Microsoft Outlook (desktop) mail
via MAPI/COM. Designed to feed verbatim messages to a local CRM per
INGESTION.md.

Prereqs: Windows, desktop Outlook installed and signed in. No cloud auth.

Usage:
    # Smoke test without MCP (prints a summary + one full email preview):
    python server.py --selftest [--days 7]

    # Run as an MCP server over STDIO (how Claude Desktop launches it):
    python server.py
"""
from __future__ import annotations

import argparse
import sys
from datetime import datetime, timedelta
from typing import Any

# pywin32 imports are gated so `--help` still works on non-Windows.
try:  # pragma: no cover — platform gate
    import pythoncom  # type: ignore
    import win32com.client  # type: ignore
    HAVE_WIN32 = True
except ImportError:
    HAVE_WIN32 = False

from mcp.server.fastmcp import FastMCP

# --- MAPI constants ---------------------------------------------------------
OL_MAIL_ITEM_CLASS = 43           # olMail — MailItem.Class
OL_FOLDER_INBOX = 6               # olFolderInbox
OL_FOLDER_SENT = 5                # olFolderSentMail

# Extended MAPI property tags via PropertyAccessor
PR_INTERNET_MESSAGE_ID = "http://schemas.microsoft.com/mapi/proptag/0x1035001F"
PR_TRANSPORT_MESSAGE_HEADERS = "http://schemas.microsoft.com/mapi/proptag/0x007D001F"

mcp = FastMCP("outlook-mail")


# --- COM plumbing -----------------------------------------------------------
def _mapi():
    if not HAVE_WIN32:
        raise RuntimeError(
            "pywin32 is not installed or this isn't Windows — this MCP requires "
            "desktop Outlook via COM. Install pywin32 and run on Windows."
        )
    pythoncom.CoInitialize()
    outlook = win32com.client.Dispatch("Outlook.Application")
    return outlook.GetNamespace("MAPI")


def _to_naive(dt) -> datetime:
    """Normalize a pywintypes.datetime (or datetime) to a naive Python datetime.

    Comparisons between pywintypes.datetime (tz-aware) and naive datetimes raise
    TypeError, so we normalize on the way in.
    """
    return datetime(dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second)


def _fmt_dt(dt) -> str:
    if dt is None:
        return ""
    try:
        return _to_naive(dt).strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        return str(dt)


def _read_prop(item, tag: str, default: str = "") -> str:
    try:
        val = item.PropertyAccessor.GetProperty(tag)
        return val if val else default
    except Exception:
        return default


def _sender_smtp(m) -> str:
    """Resolve sender to an SMTP address, walking Exchange if needed.

    For internal Exchange senders, SenderEmailAddress is an X.500-style
    /o=.../cn=... path; we resolve to SMTP via the Exchange user lookup.
    """
    addr = ""
    try:
        addr = getattr(m, "SenderEmailAddress", "") or ""
        if "@" in addr:
            return addr
        sender = getattr(m, "Sender", None)
        if sender is not None:
            try:
                eu = sender.GetExchangeUser()
                if eu is not None and eu.PrimarySmtpAddress:
                    return eu.PrimarySmtpAddress
            except Exception:
                pass
    except Exception:
        pass
    return addr


def _attachment_count(m) -> int:
    try:
        return int(m.Attachments.Count)
    except Exception:
        return 0


def _iter_mail(folder, since: datetime, folder_label: str):
    """Yield (MailItem, folder_label) for mail received on/after `since`.

    We iterate the whole folder rather than using Restrict — Restrict's date
    syntax is locale-sensitive and this scan is fast enough for 7-day windows.
    """
    for item in folder.Items:
        try:
            if getattr(item, "Class", None) != OL_MAIL_ITEM_CLASS:
                continue
            recv = getattr(item, "ReceivedTime", None) or getattr(item, "SentOn", None)
            if recv is None:
                continue
            if _to_naive(recv) < since:
                continue
            yield item, folder_label
        except Exception:
            # Corrupt/unreadable item — skip rather than fail the whole run.
            continue


def _collect(days: int, include_sent: bool, store_name: str | None = None):
    """Iterate mail across all stores. Filter to one with `store_name` if given.

    Iterating every store (not just the default delivery store) matters because
    in multi-account setups the default is often an empty PST — the user's real
    mail arrives into a non-default Exchange or IMAP store.
    """
    ns = _mapi()
    since = datetime.now() - timedelta(days=days)
    for store in ns.Stores:
        try:
            name = store.DisplayName
        except Exception:
            continue
        if store_name and name != store_name:
            continue
        try:
            inbox = store.GetDefaultFolder(OL_FOLDER_INBOX)
            yield from _iter_mail(inbox, since, f"{name}/Inbox")
        except Exception:
            pass
        if include_sent:
            try:
                sent = store.GetDefaultFolder(OL_FOLDER_SENT)
                yield from _iter_mail(sent, since, f"{name}/Sent Items")
            except Exception:
                pass


def _count_recent(folder, days: int) -> int:
    since = datetime.now() - timedelta(days=days)
    n = 0
    for item in folder.Items:
        try:
            if getattr(item, "Class", None) != OL_MAIL_ITEM_CLASS:
                continue
            recv = getattr(item, "ReceivedTime", None) or getattr(item, "SentOn", None)
            if recv is None:
                continue
            if _to_naive(recv) >= since:
                n += 1
        except Exception:
            continue
    return n


def _newest_received(folder) -> str:
    """ReceivedTime of the newest item in the folder, formatted, for diagnostics."""
    try:
        items = folder.Items
        try:
            items.Sort("[ReceivedTime]", True)  # descending
        except Exception:
            pass
        newest = items.GetFirst()
        if newest is None:
            return ""
        recv = getattr(newest, "ReceivedTime", None) or getattr(newest, "SentOn", None)
        return _fmt_dt(recv)
    except Exception:
        return ""


def _metadata(m, folder_label: str) -> dict[str, Any]:
    return {
        "entry_id": m.EntryID,
        "folder": folder_label,
        "subject": (getattr(m, "Subject", "") or "").strip(),
        "sender_name": getattr(m, "SenderName", "") or "",
        "sender_email": _sender_smtp(m),
        "received": _fmt_dt(getattr(m, "ReceivedTime", None) or getattr(m, "SentOn", None)),
        "message_id": _read_prop(m, PR_INTERNET_MESSAGE_ID),
        "size_bytes": int(getattr(m, "Size", 0) or 0),
        "attachment_count": _attachment_count(m),
    }


def _verbatim_content(m) -> str:
    """Build a raw-email string: RFC-5322 headers, blank line, then body.

    The CRM (INGESTION.md) wants verbatim plaintext with From/To/Subject/Date
    headers plus signature. We prefer the real Internet headers stored in
    PR_TRANSPORT_MESSAGE_HEADERS and fall back to a synthesized header block
    (for items with no SMTP transport headers — e.g. items composed and sent
    from Outlook itself, or drafts read from Sent Items).
    """
    body = getattr(m, "Body", "") or ""
    headers = _read_prop(m, PR_TRANSPORT_MESSAGE_HEADERS)
    if headers:
        return headers.rstrip() + "\r\n\r\n" + body

    parts = [
        f"From: {getattr(m, 'SenderName', '') or ''} <{_sender_smtp(m)}>",
        f"To: {getattr(m, 'To', '') or ''}",
    ]
    cc = getattr(m, "CC", "") or ""
    if cc:
        parts.append(f"Cc: {cc}")
    parts.append(f"Subject: {getattr(m, 'Subject', '') or ''}")
    dt = getattr(m, "ReceivedTime", None) or getattr(m, "SentOn", None)
    if dt is not None:
        parts.append(f"Date: {_fmt_dt(dt)}")
    return "\r\n".join(parts) + "\r\n\r\n" + body


# --- MCP tools --------------------------------------------------------------
@mcp.tool()
def list_recent_emails(
    days: int = 7,
    include_sent: bool = True,
    limit: int = 500,
    store_name: str | None = None,
) -> list[dict[str, Any]]:
    """List metadata for emails from the last `days` days.

    Set `include_sent=True` (default) to also include Sent Items — customer
    conversations are two-way and the CRM benefits from both sides. Each item's
    `entry_id` is what you pass to `get_email` for the verbatim payload; use
    `message_id` (RFC-5322 Message-ID) as the CRM `externalId` for stable dedup.
    Results are sorted newest first. `limit` bounds the response size.

    By default, every mail store in the Outlook profile is scanned. Pass
    `store_name` (matches `DisplayName` — call `diagnose()` to see the list) to
    restrict to one store, e.g. one account when several are configured.
    """
    out: list[dict[str, Any]] = []
    for m, label in _collect(days, include_sent, store_name):
        out.append(_metadata(m, label))
        if len(out) >= limit:
            break
    out.sort(key=lambda x: x.get("received", ""), reverse=True)
    return out


@mcp.tool()
def diagnose() -> dict[str, Any]:
    """Report on Outlook's state: profile, accounts, stores, folder item counts.

    Run this when `list_recent_emails` returns unexpectedly few results, or when
    setting up on a new machine. It enumerates every mail store visible in the
    active Outlook profile with counts for its Inbox and Sent Items (total
    items, mail items in the last 7 days, and the timestamp of the newest
    item). Use it to figure out whether the CRM is looking in the right store —
    in multi-account setups the "default" store is often an empty PST while the
    real mail lives in another store.
    """
    ns = _mapi()
    outlook = win32com.client.Dispatch("Outlook.Application")

    accounts: list[dict[str, Any]] = []
    try:
        for acct in ns.Accounts:
            try:
                accounts.append({
                    "name": acct.DisplayName,
                    "smtp": getattr(acct, "SmtpAddress", "") or "",
                    "type": int(getattr(acct, "AccountType", -1)),
                })
            except Exception as exc:
                accounts.append({"error": repr(exc)})
    except Exception as exc:
        accounts.append({"error": f"Accounts enumeration failed: {exc!r}"})

    stores: list[dict[str, Any]] = []
    try:
        for store in ns.Stores:
            info: dict[str, Any] = {}
            try:
                info["display_name"] = store.DisplayName
                info["file_path"] = getattr(store, "FilePath", "") or ""
                info["is_default"] = bool(getattr(store, "IsDataFileStore", False))
            except Exception as exc:
                info["store_error"] = repr(exc)
            for ftype, label in ((OL_FOLDER_INBOX, "inbox"), (OL_FOLDER_SENT, "sent")):
                try:
                    f = store.GetDefaultFolder(ftype)
                    info[f"{label}_total"] = int(f.Items.Count)
                    info[f"{label}_mail_last7d"] = _count_recent(f, 7)
                    info[f"{label}_newest"] = _newest_received(f)
                except Exception as exc:
                    info[f"{label}_error"] = repr(exc)
            stores.append(info)
    except Exception as exc:
        stores.append({"error": f"Stores enumeration failed: {exc!r}"})

    default_store = ""
    try:
        default_store = ns.DefaultStore.DisplayName
    except Exception:
        pass

    return {
        "outlook_version": getattr(outlook, "Version", "?"),
        "profile": getattr(ns, "CurrentProfileName", ""),
        "default_store": default_store,
        "accounts": accounts,
        "stores": stores,
        "now_local": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }


@mcp.tool()
def get_email(entry_id: str) -> dict[str, Any]:
    """Return the full verbatim content of one email, plus metadata.

    `content` is a raw plaintext RFC-5322-style message — the real Internet
    headers followed by a blank line and the body verbatim (signature included).
    This is exactly the shape the CRM's POST /api/ingest expects in its
    `content` field (see INGESTION.md). `message_id` is safe to pass as
    `externalId`; the CRM falls back to a content hash if it's blank.
    """
    ns = _mapi()
    m = ns.GetItemFromID(entry_id)
    if getattr(m, "Class", None) != OL_MAIL_ITEM_CLASS:
        raise ValueError(f"EntryID {entry_id!r} is not a mail item")
    parent = getattr(m, "Parent", None)
    folder_label = getattr(parent, "Name", "") if parent is not None else ""
    md = _metadata(m, folder_label=folder_label)
    md["content"] = _verbatim_content(m)
    return md


# --- Self-test --------------------------------------------------------------
def _selftest(days: int) -> int:
    print(f"outlook-mail selftest: last {days} days, all stores")
    print()
    print("== diagnose() ==")
    d = diagnose()
    print(f"  Outlook version: {d['outlook_version']}")
    print(f"  Profile: {d['profile']!r}")
    print(f"  Default store: {d['default_store']!r}")
    print(f"  Now (local): {d['now_local']}")
    print(f"  Accounts ({len(d['accounts'])}):")
    for a in d["accounts"]:
        print(f"    - {a}")
    print(f"  Stores ({len(d['stores'])}):")
    for s in d["stores"]:
        print(f"    - {s}")
    print()
    print("== list_recent_emails() ==")
    listing = list_recent_emails(days=days, include_sent=True, limit=1000)
    print(f"  found {len(listing)} messages in the last {days} days across all stores")
    if not listing:
        print()
        print("  Nothing found. Look at the store table above:")
        print("   - If every store's *_newest is blank, MAPI is talking to an empty")
        print("     profile (Outlook not signed in, or you're on 'New Outlook' which")
        print("     doesn't expose COM).")
        print("   - If a store has recent items but *_mail_last7d is 0, our date")
        print("     comparison is off — send me the diagnose() output to look at.")
        print("   - If your mail lives in a store that's not the default and this")
        print("     scan still found nothing, we may not have permission to open it.")
        return 0
    first = listing[0]
    print(
        f"  most recent: [{first['folder']}] {first['received']} "
        f"- {first['sender_name']} <{first['sender_email']}> "
        f"- {first['subject']!r}"
    )
    payload = get_email(first["entry_id"])
    print(f"  content bytes: {len(payload['content'])}")
    mid = payload["message_id"] or "(none - CRM will fall back to content hash)"
    print(f"  message_id: {mid}")
    print("  --- first 500 chars of content ---")
    print(payload["content"][:500])
    print("  --- end preview ---")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--selftest",
        action="store_true",
        help="Print a smoke-test summary without starting the MCP server.",
    )
    ap.add_argument(
        "--days",
        type=int,
        default=7,
        help="Days of history for --selftest (default: 7)",
    )
    args = ap.parse_args()
    if args.selftest:
        sys.exit(_selftest(args.days))
    mcp.run()
