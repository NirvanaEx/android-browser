#!/usr/bin/env bash
set -euo pipefail
# Optional Ubuntu/WSL helper: unpack missing host executables without root.
prefix=${UPGRID_HOST_TOOLS_DIR:-"$HOME/.cache/upgrid/host-tools"}
mkdir -p -- "$prefix/packages"
cd -- "$prefix/packages"
apt-get download unzip zip make m4
for package in ./*.deb; do
    dpkg-deb -x "$package" "$prefix"
done
echo "Host tools unpacked to $prefix/usr/bin"
