#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_REPOSITORY:-}" == NirvanaEx/android-browser ]]
# Only disposable GitHub-hosted VMs. Never execute this on the user's PC.
[[ "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]
sudo rm -rf -- /usr/share/dotnet /usr/local/lib/android /opt/ghc /usr/local/.ghcup /usr/share/swift
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends zstd ninja-build python3 git curl xz-utils unzip zip openjdk-17-jdk-headless
sudo install -d -o "$(id -u)" -g "$(id -g)" /home/neyron/.cache/upgrid/chromium
mkdir -p /home/runner/upgrid-state
df -h / /home/neyron/.cache/upgrid/chromium
