"""Run only our child process group, retaining a physical host disk reserve."""
import os
import pathlib
import signal
import subprocess
import threading

from preflight import inspect


def run_checked(command, *, cwd, env=None, reserve_gib=20, observer=None):
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f"Interrupted by signal {signum}")

    old_handler = signal.signal(signal.SIGTERM, interrupted)
    process = None
    reader = None
    success = False
    try:
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, env=env,
                                   start_new_session=True,
                                   stdout=subprocess.PIPE if observer else None,
                                   stderr=subprocess.STDOUT if observer else None,
                                   text=bool(observer), errors='replace' if observer else None)
        if observer:
            def consume():
                with (observer.state / 'final-build.log').open('w') as log:
                    for line in process.stdout:
                        log.write(line)
                        log.flush()
                        print(line, end='', flush=True)
                        observer.line(line)
            reader = threading.Thread(target=consume, daemon=True)
            reader.start()
            observer.heartbeat(process.pid)
        while True:
            try:
                code = process.wait(timeout=30)
                if code:
                    raise subprocess.CalledProcessError(code, command)
                success = True
                return
            except subprocess.TimeoutExpired:
                if observer:
                    observer.heartbeat(process.pid)
                status = inspect(pathlib.Path(cwd), min_free_gib=reserve_gib)
                if not status["storageVerified"] or status["physicalFreeGiB"] < reserve_gib:
                    raise RuntimeError("Stopping this process to preserve host disk space: " + str(status))
    finally:
        try:
            if process is not None and process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
        finally:
            if reader:
                reader.join(timeout=10)
            if observer and process is not None:
                observer.heartbeat(process.pid, 'success' if success else 'failure')
            signal.signal(signal.SIGTERM, old_handler)
