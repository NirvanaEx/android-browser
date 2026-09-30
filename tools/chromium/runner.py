"""Run only our child process group, retaining a physical host disk reserve."""
import os
import pathlib
import signal
import subprocess

from preflight import inspect


def run_checked(command, *, cwd, env=None, reserve_gib=20):
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f"Interrupted by signal {signum}")

    old_handler = signal.signal(signal.SIGTERM, interrupted)
    process = None
    try:
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, env=env,
                                   start_new_session=True)
        while True:
            try:
                code = process.wait(timeout=30)
                if code:
                    raise subprocess.CalledProcessError(code, command)
                return
            except subprocess.TimeoutExpired:
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
            signal.signal(signal.SIGTERM, old_handler)
