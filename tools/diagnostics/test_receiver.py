import copy
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
import receiver


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.database = str(Path(self.temp.name) / "reports.sqlite3")
        self.token = "test-only-ingest-token-does-not-read-reports"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), receiver.handler(self.database, self.token))
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}/upgrid-diagnostics/v1/reports"
        self.report = dict(schema=1, id=str(uuid.uuid4()), installation=str(uuid.uuid4()), kind="java_crash", build="test", timestamp=1, source="test", device={}, details={}, exceptions=[], breadcrumbs=[])

    def send(self, report=None, token=None, method="POST"):
        data = None if method == "GET" else json.dumps(report if report is not None else self.report).encode()
        request = urllib.request.Request(self.url, data=data, headers={"Authorization": "Bearer " + (token if token is not None else self.token), "Content-Type": "application/json"}, method=method)
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def test_authentication_and_no_public_reads(self):
        self.assertEqual(self.send(token="wrong")[0], 401)
        self.assertEqual(self.send(method="GET")[0], 404)

    def test_persists_once_and_retries_are_idempotent(self):
        self.assertEqual(self.send()[0], 201)
        status, body = self.send()
        self.assertEqual(status, 200)
        self.assertTrue(body["duplicate"])
        with receiver.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM reports").fetchone()[0], 1)

    def test_id_cannot_overwrite_another_report(self):
        self.send()
        other = copy.deepcopy(self.report)
        other["source"] = "different"
        self.assertEqual(self.send(other)[0], 409)

    def test_rejects_unknown_fields_invalid_ids_and_oversize(self):
        for key, value in (("password", "secret"), ("id", "../not-a-uuid"), ("kind", "unknown")):
            report = dict(self.report)
            report[key] = value
            self.assertEqual(self.send(report)[0], 400)
        report = dict(self.report, details={"body": "x" * receiver.MAX_BYTES})
        self.assertEqual(self.send(report)[0], 413)


if __name__ == "__main__":
    unittest.main()
