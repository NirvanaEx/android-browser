"""Run as root on the user's existing Upgrid VPS after staging these files."""
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import time

here = Path(__file__).resolve().parent
destination = Path("/opt/upgrid-diagnostics")
destination.mkdir(mode=0o755, exist_ok=True)
for name in ("receiver.py", "nginx-location.inc"):
    shutil.copyfile(here / name, destination / name)
    (destination / name).chmod(0o644)
environment = Path("/etc/upgrid-diagnostics.env")
if not environment.exists():
    environment.touch(mode=0o600)
    environment.write_text("UPGRID_INGEST_TOKEN=" + secrets.token_urlsafe(32) + "\n")
environment.chmod(0o600)
values = dict(line.split("=", 1) for line in environment.read_text().splitlines() if "=" in line)
token = values["UPGRID_INGEST_TOKEN"]
if len(token) < 32:
    raise SystemExit("Invalid existing ingest credential")
shutil.copyfile(here / "upgrid-diagnostics.service", "/etc/systemd/system/upgrid-diagnostics.service")
vhost = Path("/www/server/panel/vhost/nginx/upgrid-api.conf")
original = vhost.read_text()
if "server_name ai-game.193-160-119-15.sslip.io;" not in original:
    raise SystemExit("Unexpected Upgrid virtual host")
include = "    include /opt/upgrid-diagnostics/nginx-location.inc;"
if include not in original:
    anchor = "    location / { return 404; }"
    if original.count(anchor) != 1:
        raise SystemExit("Unexpected Upgrid virtual host layout")
    backup = vhost.with_name(vhost.name + ".before-diagnostics-" + str(int(time.time())))
    shutil.copy2(vhost, backup)
    vhost.write_text(original.replace(anchor, include + "\n\n" + anchor))
    test = subprocess.run(["/www/server/nginx/sbin/nginx", "-t"], capture_output=True)
    if test.returncode:
        vhost.write_text(original)
        raise SystemExit("Nginx validation failed; original virtual host restored")
subprocess.run(["systemctl", "daemon-reload"], check=True)
subprocess.run(["systemctl", "enable", "--now", "upgrid-diagnostics"], check=True)
subprocess.run(["systemctl", "restart", "upgrid-diagnostics"], check=True)
subprocess.run(["/www/server/nginx/sbin/nginx", "-t"], check=True)
subprocess.run(["/www/server/nginx/sbin/nginx", "-s", "reload"], check=True)
config = Path("/root/projects/apk-relay/work/upgrid-diagnostics-config.json")
config.touch(mode=0o600, exist_ok=True)
config.chmod(0o600)
config.write_text(json.dumps({"endpoint": "https://ai-game.193-160-119-15.sslip.io/upgrid-diagnostics/v1/reports", "token": token}))
print("Receiver installed. Build credential saved privately; no report-reading HTTP endpoint.")
