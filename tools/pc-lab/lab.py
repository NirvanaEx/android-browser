#!/usr/bin/env python3
"""Local Windows/WSL Android workbench. Only controls its own persistent AVD."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "build/fenix/pc-lab"
PACKAGE = "com.upgrid.browser.next.debug"
ACTIVITY = PACKAGE + "/org.mozilla.fenix.HomeActivity"
AVD = "Upgrid_PC"
SERIAL = "emulator-5580"
PORT = 8766
HOST_PORT = 8876
PAGES = {name: name + ".html" for name in (
    "lab", "native", "engine", "iframe", "multiple", "controls", "aspect",
    "tabs", "translation", "media-scroll",
)}


class LabBusy(RuntimeError):
    pass


def say(message):
    print(message, flush=True)


def decode_output(data):
    # wsl.exe's own Windows errors are UTF-16LE; Linux commands return UTF-8.
    encoding = "utf-16-le" if data[:400].count(b"\0") > 10 else "utf-8"
    return data.decode(encoding, "replace")


def run(argv, timeout=60, check=True, binary=False, env=None):
    result = subprocess.run([str(a) for a in argv], cwd=ROOT, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if check and result.returncode:
        detail = (decode_output(result.stdout) + decode_output(result.stderr))[-2500:]
        raise RuntimeError(f"{Path(str(argv[0])).name} failed ({result.returncode}): {detail}")
    return result.stdout if binary else decode_output(result.stdout).strip()


def source_paths(root):
    paths = list((root / "app/src/main").rglob("*"))
    paths += list((root / "tools/fenix").rglob("*"))
    paths += [root / "tools/pc-lab/build.sh", root / "tools/pc-lab/mozconfig"]
    return sorted(path for path in paths if path.is_file() and "__pycache__" not in path.parts
                  and path.suffix not in (".md", ".pyc", ".log"))


def fingerprint(root=None):
    """Content hashes catch edits/deletes, even while a build is running."""
    root = ROOT if root is None else root
    digest = hashlib.sha256()
    for path in source_paths(root):
        content = path.read_bytes()
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        digest.update(str(len(content)).encode() + b"\0" + content)
    return digest.hexdigest()


def snapshot_sources():
    """Freeze overlay inputs before compilation; never build from live editor files."""
    before = fingerprint()
    snapshot = DATA / "sources" / (time.strftime("%Y%m%d-%H%M%S") + f"-{time.time_ns() % 1000000:06d}")
    snapshot.mkdir(parents=True, exist_ok=False)
    for source in source_paths(ROOT):
        target = snapshot / source.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
    if fingerprint(snapshot) != before or fingerprint() != before:
        raise RuntimeError("Sources changed while taking snapshot; retry after editing settles.")
    return snapshot, before


def validate_apk(path):
    with zipfile.ZipFile(path) as archive:
        abis = [abi for abi in ("x86_64", "arm64-v8a") if f"lib/{abi}/libxul.so" in archive.namelist()]
        if not abis:
            raise RuntimeError("APK lacks Gecko for x86_64 or ARM64. Do not use the empty x86_64 split from the phone build.")
        return abis


@contextlib.contextmanager
def exclusive_operation(kind="operation"):
    """OS releases this lock on failure/Ctrl+C; no stale PID lock files."""
    import msvcrt
    if kind not in ("operation", "device"):
        raise ValueError("Unknown operation lock")
    DATA.mkdir(parents=True, exist_ok=True)
    with (DATA / f"{kind}.lock").open("a+b") as handle:
        handle.seek(0)
        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise LabBusy(f"Another operation holds the PC lab {kind} lock; waiting for it to finish.") from error
        try:
            if os.fstat(handle.fileno()).st_size == 0:
                handle.write(b"0")
                handle.flush()
            yield
        finally:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)


class Lab:
    def __init__(self, distro="Ubuntu"):
        self.distro = distro
        self.sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
                        or Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk")
        self.adb_exe = self.sdk / "platform-tools/adb.exe"
        self.emulator = self.sdk / "emulator/emulator.exe"
        self.avds = DATA / "avd"
        self.env = dict(os.environ, ANDROID_AVD_HOME=str(self.avds))
        DATA.mkdir(parents=True, exist_ok=True)

    def adb(self, *args, **kwargs):
        return run([self.adb_exe, "-s", SERIAL, *args], **kwargs)

    def shell(self, *args, **kwargs):
        # adb shell reparses the command remotely; quoting each argument is essential.
        return self.adb("shell", shlex.join([str(a) for a in args]), **kwargs)

    def wsl(self, *args, **kwargs):
        return run(["wsl.exe", "-d", self.distro, "--exec", *args], **kwargs)

    def active_builds(self):
        # Do not count idle Gradle daemons or our own inspection command as builds.
        code = '''import pathlib,json
found=[]
for p in pathlib.Path('/proc').glob('[0-9]*'):
 try:
  args=(p/'cmdline').read_bytes().decode(errors='replace').split(chr(0))
  cwd=str((p/'cwd').resolve())
  wrapper=any(a.endswith(('tools/fenix/build.sh','tools/pc-lab/build.sh')) or (a=='build.sh' and cwd.endswith(('/tools/fenix','/tools/pc-lab'))) for a in args)
  mach=any(pathlib.Path(a).name=='mach' for a in args) and any(a in ('build','gradle') for a in args)
  if wrapper or (mach and 'firefox-' in cwd): found.append({'pid':int(p.name),'task':' '.join(args[1:5])})
 except (OSError,ValueError): pass
print(json.dumps(found))'''
        return json.loads(self.wsl("python3", "-c", code))

    def require_idle(self, optimized_only=False):
        active = self.active_builds()
        if optimized_only:
            active = [item for item in active if "Release" in item["task"] or "build" in item["task"]]
        if active:
            raise LabBusy("An existing Fenix build is active; left untouched: " + json.dumps(active))

    def connected(self):
        result = run([self.adb_exe, "devices"])
        return any(line.split() == [SERIAL, "device"] for line in result.splitlines())

    def require_device(self):
        if not self.connected():
            raise RuntimeError("PC lab is not running. Run Start first.")
        name = self.adb("emu", "avd", "name").splitlines()[0].strip()
        if name != AVD:
            raise RuntimeError(f"{SERIAL} belongs to {name}, not {AVD}; left untouched.")

    def prepare_avd(self):
        config = self.avds / (AVD + ".avd/config.ini")
        if config.exists():
            return
        image = self.sdk / "system-images/android-34/google_apis/x86_64"
        if not (image / "system.img").exists():
            raise RuntimeError("Install Android 34 Google APIs x86_64 system image in Android SDK Manager first.")
        config.parent.mkdir(parents=True, exist_ok=True)
        settings = {
            "AvdId": AVD, "avd.ini.displayname": "Upgrid PC test device", "avd.ini.encoding": "UTF-8",
            "abi.type": "x86_64", "hw.cpu.arch": "x86_64", "hw.cpu.ncore": "2", "hw.ramSize": "2048",
            "hw.lcd.width": "720", "hw.lcd.height": "1280", "hw.lcd.density": "320",
            "hw.gpu.enabled": "yes", "hw.gpu.mode": "auto", "hw.keyboard": "yes", "hw.mainKeys": "no",
            "hw.accelerometer": "yes", "hw.sensors.orientation": "yes", "hw.battery": "yes",
            "hw.audioInput": "yes", "hw.audioOutput": "yes", "hw.camera.back": "none", "hw.camera.front": "none",
            "hw.device.manufacturer": "Google", "hw.device.name": "pixel_3a", "hw.sdCard": "no",
            "disk.dataPartition.size": "6G", "vm.heapSize": "256", "showDeviceFrame": "no",
            "image.sysdir.1": str(image) + os.sep, "tag.id": "google_apis", "tag.display": "Google APIs",
            "target": "android-34", "PlayStore.enabled": "false", "runtime.network.latency": "none",
            "runtime.network.speed": "full",
        }
        config.write_text("".join(f"{key}={value}\n" for key, value in settings.items()), encoding="utf-8")
        (self.avds / (AVD + ".ini")).write_text(
            f"avd.ini.encoding=UTF-8\npath={config.parent}\ntarget=android-34\n", encoding="utf-8")

    def start(self):
        if not self.connected():
            self.require_idle(optimized_only=True)
            for port in (5580, 5581):
                with socket.socket() as probe:
                    if probe.connect_ex(("127.0.0.1", port)) == 0:
                        raise RuntimeError(f"Port {port} is already occupied; no device was started.")
            self.prepare_avd()
            with (DATA / "emulator.log").open("ab") as log:
                process = subprocess.Popen(
                    [str(self.emulator), "-avd", AVD, "-port", "5580", "-memory", "2048", "-cores", "2",
                     "-no-boot-anim", "-no-snapshot-load", "-qemu", "-m", "2048"],
                    env=self.env, stdout=log, stderr=log,
                    creationflags=subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS)
            say(f"Starting Android window (2 GB, 2 cores, BelowNormal); launcher PID {process.pid}.")
        deadline = time.monotonic() + 360
        next_update = 0
        while time.monotonic() < deadline:
            if self.connected() and self.shell("getprop", "sys.boot_completed", check=False) == "1":
                self.require_device()
                self.shell("input", "keyevent", "KEYCODE_WAKEUP")
                self.shell("wm", "dismiss-keyguard", check=False)
                self.fixtures()
                say("Android ready: " + SERIAL)
                return
            if time.monotonic() > next_update:
                say("Waiting for Android boot...")
                next_update = time.monotonic() + 20
            time.sleep(2)
        raise RuntimeError(f"Android did not finish booting. See {DATA / 'emulator.log'}")

    def fixtures(self):
        self.require_device()
        # Isolate mutable fixture state from the ordinary test server on the PC.
        url = f"http://127.0.0.1:{HOST_PORT}/lab.html"
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                content = response.read(65536)
            if b"upgrid-pc-lab-v1" not in content:
                raise RuntimeError(f"Port {HOST_PORT} serves another application. It was left untouched.")
        except (OSError, urllib.error.URLError):
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", HOST_PORT)) == 0:
                    raise RuntimeError(f"Port {HOST_PORT} is occupied by an older/other server. Stop it explicitly first.")
            node = shutil.which("node")
            if not node:
                raise RuntimeError("Node.js is required to serve local test pages.")
            with (DATA / "fixtures.log").open("ab") as log:
                subprocess.Popen([node, str(ROOT / "tools/tests/player-server.cjs")], cwd=ROOT,
                                 stdout=log, stderr=log, env=dict(os.environ, UPGRID_FIXTURE_PORT=str(HOST_PORT)),
                                 creationflags=subprocess.CREATE_NO_WINDOW)
            for attempt in range(20):
                try:
                    with urllib.request.urlopen(url, timeout=1) as response:
                        if b"upgrid-pc-lab-v1" in response.read(65536):
                            break
                except OSError:
                    time.sleep(.25)
            else:
                raise RuntimeError("Test page server failed. See fixtures.log.")
        self.adb("reverse", f"tcp:{PORT}", f"tcp:{HOST_PORT}")
        if not (ROOT / "build/fenix/sample.mp4").exists():
            say("Note: sample.mp4 is missing; video fixtures need a local synthetic H.264/AAC clip.")

    def installed_version(self):
        result = self.shell("dumpsys", "package", PACKAGE)
        return "\n".join(line.strip() for line in result.splitlines()
                         if re.match(r"\s*(versionCode=|versionName=|primaryCpuAbi=)", line))

    def installed_hash(self):
        paths = self.shell("pm", "path", PACKAGE).splitlines()
        base = next((line.removeprefix("package:") for line in paths if line.endswith("/base.apk")), None)
        if not base:
            raise RuntimeError("Installed base.apk was not found.")
        value = self.shell("sha256sum", base, timeout=60).split()[0]
        if not re.fullmatch(r"[a-fA-F0-9]{64}", value):
            raise RuntimeError("Could not verify the installed APK hash.")
        return value.lower()

    def install(self, apk):
        self.require_device()
        apk = Path(apk).resolve()
        engine_abis = validate_apk(apk)
        aapts = sorted(self.sdk.glob("build-tools/*/aapt.exe"))
        if not aapts:
            raise RuntimeError("Android build-tools/aapt.exe is required to verify the package.")
        badging = run([aapts[-1], "dump", "badging", apk])
        if not re.search(r"^package: name='" + re.escape(PACKAGE) + "' ", badging):
            raise RuntimeError("APK package is not Upgrid Next; not installed.")
        abis = self.shell("getprop", "ro.product.cpu.abilist")
        if not set(engine_abis).intersection(abis.split(",")):
            raise RuntimeError(f"This image cannot run the browser (engine: {engine_abis}; device: {abis}).")
        say("Installing with profile preserved: " + apk.name)
        result = self.adb("install", "-r", str(apk), timeout=240)
        if "Success" not in result:
            raise RuntimeError("Installation not confirmed: " + result)
        with apk.open("rb") as file:
            apk_hash = hashlib.file_digest(file, "sha256").hexdigest()
        if self.installed_hash() != apk_hash:
            raise RuntimeError("Installed APK hash differs from the selected file.")
        receipt = {"apk": str(apk), "sha256": apk_hash,
                   "installedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "version": self.installed_version()}
        build_receipt = DATA / "build.json"
        if apk == (DATA / "current.apk").resolve() and build_receipt.exists():
            receipt["build"] = json.loads(build_receipt.read_text(encoding="utf-8"))
        (DATA / "installed.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
        say(receipt["version"])
        self.open_page(None)

    def open_page(self, page):
        self.require_device()
        args = ["am", "start", "-n", ACTIVITY]
        if page:
            self.fixtures()
            url = f"http://127.0.0.1:{PORT}/{PAGES[page]}"
            # Fenix's IntentReceiverActivity processes external URLs. Sending
            # VIEW directly to HomeActivity opens home but silently loses the URL.
            args = ["am", "start", "-a", "android.intent.action.VIEW", "-d", url, "-p", PACKAGE]
        result = self.shell(*args)
        if "Error" in result or "Exception" in result:
            raise RuntimeError(result)
        say(result)

    def capture(self, note):
        self.require_device()
        folder = DATA / "reports" / time.strftime("%Y%m%d-%H%M%S")
        folder.mkdir(parents=True, exist_ok=False)
        errors = []
        commands = {
            "logcat.txt": ("logcat", "-d", "-t", "3000", "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime"),
            "activity.txt": ("shell", "dumpsys activity activities"),
            "memory.txt": ("shell", "dumpsys meminfo " + PACKAGE),
            "display-size.txt": ("shell", "wm size"),
            "display-density.txt": ("shell", "wm density"),
            "guest-memory.txt": ("shell", "cat /proc/meminfo"),
        }
        for name, command in commands.items():
            try:
                (folder / name).write_text(self.adb(*command, timeout=30), encoding="utf-8")
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                errors.append(f"{name}: {error}")
        try:
            (folder / "screen.png").write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))
            helper = ROOT / "build/fenix/android-ui/snapshot.jar"
            remote = "/data/local/tmp/upgrid-pc-lab.xml"
            if helper.exists():
                self.adb("push", str(helper), "/data/local/tmp/upgrid-pc-snapshot.jar")
                dump = self.adb("shell", "CLASSPATH=/data/local/tmp/upgrid-pc-snapshot.jar app_process /system/bin "
                                "com.upgrid.uitest.Snapshot " + remote + " 1500", timeout=30)
                if "SNAPSHOT_OK" not in dump:
                    raise RuntimeError("UI helper did not confirm a fresh snapshot; old XML was not copied.")
            else:
                dump = self.shell("uiautomator", "dump", remote, timeout=30)
                if "dumped to:" not in dump:
                    raise RuntimeError("uiautomator did not confirm a fresh snapshot.")
            self.adb("pull", remote, str(folder / "ui.xml"))
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            errors.append("UI: " + str(error))
        metadata = {"createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "serial": SERIAL, "avd": AVD,
                    "version": self.installed_version(), "android": self.shell("getprop", "ro.build.version.release"),
                    "abi": self.shell("getprop", "ro.product.cpu.abilist"), "note": note, "errors": errors}
        try:
            metadata["installedApkSha256"] = self.installed_hash()
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            errors.append("APK hash: " + str(error))
        installed = DATA / "installed.json"
        if installed.exists():
            metadata["installationReceipt"] = json.loads(installed.read_text(encoding="utf-8"))
            metadata["installationReceipt"]["matchesCurrentVersion"] = (
                metadata["installationReceipt"]["version"] == metadata["version"])
            metadata["installationReceipt"]["matchesCurrentApk"] = (
                metadata["installationReceipt"]["sha256"] == metadata.get("installedApkSha256"))
        (folder / "report.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
        (folder / "steps.txt").write_text(
            f"Описание: {note}\n\nШаги воспроизведения:\n1. \n2. \n\nОжидалось:\n\nПроизошло:\n\n"
            "Локальный отчёт. Скриншоты, интерфейс и логи могут содержать данные тестовых страниц.\n", encoding="utf-8")
        archive = shutil.make_archive(str(folder), "zip", folder)
        say(f"Report saved locally: {folder}")
        say("ZIP: " + archive)
        for error in errors:
            say("Partial capture: " + error)
        return folder

    def build(self):
        self.require_idle()
        snapshot, before = snapshot_sources()
        git_head = run(["git", "rev-parse", "HEAD"])
        stamp = time.strftime("%Y%m%d-%H%M%S")
        log_path = DATA / f"build-{stamp}.log"
        wsl_root = self.wsl("wslpath", "-u", snapshot.as_posix())
        destination = self.wsl("wslpath", "-u", (DATA / "candidate.apk").as_posix())
        diagnostics = self.wsl("wslpath", "-u", (ROOT / "build/fenix/diagnostics-config.json").as_posix())
        command = (f"cd -- {shlex.quote(wsl_root)} && UPGRID_DIAGNOSTICS_CONFIG={shlex.quote(diagnostics)} "
                   f"bash tools/pc-lab/build.sh {shlex.quote(destination)}")
        say(f"Incremental debug build (1 worker, heap 4 GB); log: {log_path}")
        with log_path.open("wb") as log:
            process = subprocess.Popen(["wsl.exe", "-d", self.distro, "--exec", "bash", "-lc", command],
                                       stdout=log, stderr=log, creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                while process.poll() is None:
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        say("Build running; Android profile is preserved. See " + log_path.name)
            except KeyboardInterrupt:
                say("Stopped waiting. The WSL build may still be running; check status before another build.")
                raise
        if process.returncode:
            raise RuntimeError(f"Build failed ({process.returncode}); installed app unchanged. See {log_path}")
        if fingerprint(snapshot) != before:
            raise RuntimeError("Build snapshot changed; candidate not installed. Build again.")
        candidate = DATA / "candidate.apk"
        if "x86_64" not in validate_apk(candidate):
            raise RuntimeError("PC build did not produce native x86_64 Gecko; installed app unchanged.")
        candidate.replace(DATA / "current.apk")
        (DATA / "build.json").write_text(json.dumps({"fingerprint": before, "log": str(log_path),
            "sourceSnapshot": str(snapshot), "gitHead": git_head,
            "version": json.loads((snapshot / "tools/fenix/release.json").read_text())}, indent=2), encoding="utf-8")
        say("Debug APK ready: " + str(DATA / "current.apk"))
        if fingerprint() != before:
            say("New edits arrived during compilation. This APK uses the saved source snapshot; build again or keep watch running to apply newer code.")
        return before

    def watch(self, once=False):
        say("Watching browser sources; debounced rebuild + install. Ctrl+C stops watching.")
        last_attempt = None
        while True:
            current = fingerprint()
            if current == last_attempt:
                time.sleep(2)
                continue
            time.sleep(2)
            if fingerprint() != current:
                continue
            try:
                active = self.active_builds()
            except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                say(f"WSL inspection unavailable; retrying in 20 seconds: {error}")
                time.sleep(20)
                continue
            if active:
                say("Existing build is busy; waiting without changing its checkout.")
                time.sleep(20)
                continue
            last_attempt = current
            try:
                with exclusive_operation():
                    last_attempt = self.build()
                    with exclusive_operation("device"):
                        self.start()
                        self.install(DATA / "current.apk")
                if once:
                    return
            except LabBusy as error:
                say(str(error))
                last_attempt = None
                time.sleep(20)
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                if once:
                    raise
                say(str(error) + " Waiting for next source edit or restart watch to retry.")

    def status(self):
        say("SDK: " + str(self.sdk))
        say("Active builds: " + json.dumps(self.active_builds()))
        say(run([self.adb_exe, "devices", "-l"]))
        if self.connected():
            self.require_device()
            say(self.installed_version())
        if (DATA / "installed.json").exists():
            say((DATA / "installed.json").read_text(encoding="utf-8"))


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["status", "start", "build", "watch", "install", "open", "restart", "capture", "save", "restore", "stop"])
    parser.add_argument("--distro", default="Ubuntu")
    parser.add_argument("--apk", type=Path, help="Explicit existing APK; otherwise use this lab's successful debug build")
    parser.add_argument("--page", choices=sorted(PAGES), default="lab")
    parser.add_argument("--note", default="")
    parser.add_argument("--once", action="store_true", help="Watch: wait for idle, build and install once, then exit")
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("Run from Windows Python 3.11+, not inside WSL.")
    lab = Lab(args.distro)
    try:
        if args.action == "watch":
            lab.watch(once=args.once)
        elif args.action == "status":
            lab.status()
        elif args.action == "build":
            with exclusive_operation():
                lab.build()
                with exclusive_operation("device"):
                    lab.start()
                    lab.install(DATA / "current.apk")
        else:
            with exclusive_operation("device"):
                if args.action == "start":
                    lab.start()
                    if args.apk:
                        lab.install(args.apk)
                    elif lab.shell("pm", "path", PACKAGE):
                        lab.open_page(None)
                        say(lab.installed_version())
                    elif (DATA / "current.apk").exists():
                        lab.install(DATA / "current.apk")
                    else:
                        say("Device ready. Choose Build + install, or pass --apk with an existing APK.")
                elif args.action == "install":
                    lab.install(args.apk or DATA / "current.apk")
                elif args.action == "open":
                    lab.open_page(args.page)
                elif args.action == "capture":
                    lab.capture(args.note)
                elif args.action == "restart":
                    lab.require_device()
                    lab.shell("am", "force-stop", PACKAGE)
                    lab.open_page(None)
                elif args.action in ("save", "restore"):
                    lab.require_device()
                    result = lab.adb("emu", "avd", "snapshot", "save" if args.action == "save" else "load", "upgrid-repro", timeout=120)
                    if "KO:" in result:
                        raise RuntimeError(result)
                    say(result)
                elif args.action == "stop":
                    lab.require_device()
                    say(lab.adb("emu", "kill"))
                    deadline = time.monotonic() + 60
                    while time.monotonic() < deadline:
                        with socket.socket() as probe:
                            if probe.connect_ex(("127.0.0.1", 5580)) != 0:
                                break
                        time.sleep(1)
                    else:
                        raise RuntimeError("Android is still shutting down; wait before starting it again.")
    except (RuntimeError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        say("ERROR: " + str(error))
        return 1
    except KeyboardInterrupt:
        say("Stopped. Android and its saved profile are left available.")
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
