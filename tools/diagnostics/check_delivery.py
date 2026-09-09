"""Send one synthetic report over the same HTTPS route used by the APK."""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("config", type=Path)
args = parser.parse_args()
config = json.loads(args.config.read_text(encoding="utf-8-sig"))
report = dict(schema=1, id=str(uuid.uuid4()), installation=str(uuid.uuid4()), build="receiver-self-test",
              timestamp=int(time.time() * 1000), kind="diagnostic_test", source="https_delivery_check",
              device={"model": "receiver-self-test"}, details={}, exceptions=[], breadcrumbs=[])
request = urllib.request.Request(config["endpoint"], data=json.dumps(report).encode(),
    headers={"Authorization": "Bearer " + config["token"], "Content-Type": "application/json"}, method="POST")
with urllib.request.urlopen(request, timeout=20) as response:
    body = json.load(response)
    assert response.status == 201 and body["accepted"] == report["id"]
    print(json.dumps({"status": response.status, "report_id": body["accepted"]}))
