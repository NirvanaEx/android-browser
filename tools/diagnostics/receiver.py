"""Write-only Upgrid diagnostics endpoint. Report contents are untrusted data."""
import argparse
from contextlib import contextmanager
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

MAX_BYTES = 96 * 1024
KINDS = {"session_start", "java_crash", "native_crash", "handled_error", "anr_watchdog", "process_exit", "player_error", "diagnostic_test"}
FIELDS = {"schema", "id", "installation", "build", "timestamp", "kind", "source", "device", "exceptions", "details", "breadcrumbs"}


@contextmanager
def connect(database):
    connection = sqlite3.connect(database, timeout=15)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, received INTEGER NOT NULL, installation TEXT NOT NULL, kind TEXT NOT NULL, build TEXT NOT NULL, sha256 TEXT NOT NULL, payload TEXT NOT NULL)")
    connection.execute("CREATE INDEX IF NOT EXISTS reports_received ON reports(received)")
    connection.execute("CREATE INDEX IF NOT EXISTS reports_installation_received ON reports(installation, received)")
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def validate(report):
    if not isinstance(report, dict) or set(report) - FIELDS:
        raise ValueError("Unexpected fields")
    if report.get("schema") != 1 or report.get("kind") not in KINDS:
        raise ValueError("Unsupported schema/kind")
    for key in ("id", "installation"):
        if not isinstance(report.get(key), str) or str(uuid.UUID(report[key])) != report[key]:
            raise ValueError("Invalid identifier")
    for key in ("build", "source"):
        if not isinstance(report.get(key), str) or not 1 <= len(report[key]) <= 120:
            raise ValueError("Invalid label")
    if type(report.get("timestamp")) is not int or report["timestamp"] < 0:
        raise ValueError("Invalid timestamp")
    for key in ("device", "details"):
        if not isinstance(report.get(key), dict):
            raise ValueError("Invalid object")
    for key, maximum in (("exceptions", 8), ("breadcrumbs", 40)):
        if not isinstance(report.get(key), list) or len(report[key]) > maximum:
            raise ValueError("Invalid list")


def handler(database, token):
    class Receiver(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # Never log tokens or report bodies to the shared web log.

        def reply(self, status, value):
            body = json.dumps(value, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.reply(404, {"error": "not_found"})

        def do_POST(self):
            if self.path != "/upgrid-diagnostics/v1/reports":
                return self.reply(404, {"error": "not_found"})
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + token).encode()):
                return self.reply(401, {"error": "unauthorized"})
            if self.headers.get("Transfer-Encoding"):
                return self.reply(400, {"error": "content_length_required"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                return self.reply(400, {"error": "invalid_length"})
            if not 0 < length <= MAX_BYTES:
                return self.reply(413, {"error": "too_large"})
            if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                return self.reply(415, {"error": "json_required"})
            self.connection.settimeout(10)
            try:
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError("Truncated body")
                report = json.loads(raw)
                validate(report)
                payload = json.dumps(report, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
            except (ValueError, TypeError, RecursionError, OSError):
                return self.reply(400, {"error": "invalid_report"})
            digest = hashlib.sha256(payload.encode()).hexdigest()
            now = int(time.time())
            with connect(database) as db:
                # Serialize quota checks and insertion across concurrent requests.
                db.execute("BEGIN IMMEDIATE")
                prior = db.execute("SELECT sha256 FROM reports WHERE id=?", (report["id"],)).fetchone()
                if prior:
                    if prior[0] != digest:
                        return self.reply(409, {"error": "id_conflict"})
                    return self.reply(200, {"accepted": report["id"], "duplicate": True})
                count = db.execute("SELECT count(*) FROM reports WHERE installation=? AND received>?", (report["installation"], now - 86400)).fetchone()[0]
                if count >= 300:
                    return self.reply(429, {"error": "daily_quota"})
                db.execute("DELETE FROM reports WHERE received<?", (now - 14 * 86400,))
                if db.execute("SELECT count(*) FROM reports").fetchone()[0] >= 10000:
                    return self.reply(503, {"error": "storage_limit"})
                db.execute("INSERT INTO reports VALUES (?,?,?,?,?,?,?)", (report["id"], now, report["installation"], report["kind"], report["build"], digest, payload))
            self.reply(201, {"accepted": report["id"], "duplicate": False})

    return Receiver


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("serve", "list", "show"))
    parser.add_argument("--database", default=os.environ.get("UPGRID_DATABASE", "/var/lib/upgrid-diagnostics/reports.sqlite3"))
    parser.add_argument("--id")
    parser.add_argument("--limit", type=int, default=20)
    args = parser.parse_args()
    if args.command == "serve":
        token = os.environ["UPGRID_INGEST_TOKEN"]
        if len(token) < 32:
            raise SystemExit("Ingest token must be at least 32 characters")
        Path(args.database).parent.mkdir(parents=True, exist_ok=True)
        with connect(args.database):
            pass
        ThreadingHTTPServer(("127.0.0.1", 28610), handler(args.database, token)).serve_forever()
    else:
        with connect(args.database) as db:
            if args.command == "show":
                row = db.execute("SELECT payload FROM reports WHERE id=?", (args.id,)).fetchone()
                print(json.dumps(json.loads(row[0]), ensure_ascii=False, indent=2) if row else "Not found")
            else:
                rows = db.execute("SELECT id,received,kind,build,payload FROM reports ORDER BY received DESC LIMIT ?", (min(max(args.limit, 1), 100),)).fetchall()
                for row in rows:
                    report = json.loads(row[4])
                    print(json.dumps({"id": row[0], "received": row[1], "kind": row[2], "build": row[3], "device": report.get("device"), "source": report.get("source")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
