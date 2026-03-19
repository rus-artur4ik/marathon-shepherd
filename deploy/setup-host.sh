#!/usr/bin/env bash
set -euo pipefail

# setup-host.sh — Bootstrap an Android Test Farm host (emulators + physical devices).
# Run as root on a fresh ARM64 Linux machine.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "============================================"
echo " Android Test Farm — Host Setup"
echo "============================================"

# ── 1. Check KVM ──────────────────────────────────────────────────────────────
echo ""
echo ">>> Checking KVM support ..."
if [ ! -e /dev/kvm ]; then
    echo "ERROR: /dev/kvm not found. Make sure KVM is enabled in BIOS/hypervisor."
    echo "       On ARM64 hosts this typically requires '-enable-kvm' or nested virt."
    exit 1
fi
echo "    /dev/kvm is present."

# ── 2. Install Docker ────────────────────────────────────────────────────────
echo ""
echo ">>> Installing Docker ..."
if ! command -v docker &>/dev/null; then
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker
    echo "    Docker installed."
else
    echo "    Docker is already installed."
fi

# ── 3. Install ADB ───────────────────────────────────────────────────────────
echo ""
echo ">>> Installing ADB ..."
if ! command -v adb &>/dev/null; then
    apt-get update -qq
    apt-get install -y --no-install-recommends android-tools-adb
    echo "    ADB installed."
else
    echo "    ADB is already installed."
fi

# ── 4. Configure ADB systemd service ─────────────────────────────────────────
echo ""
echo ">>> Setting up ADB server systemd service ..."
cp "${SCRIPT_DIR}/adb-server.service" /etc/systemd/system/adb-server.service
systemctl daemon-reload
systemctl enable --now adb-server.service
echo "    ADB server is running on port 5037 (all interfaces)."

# ── 5. Build farm-server Docker image ────────────────────────────────────────
echo ""
echo ">>> Building farm-server Docker image ..."
cd "${REPO_ROOT}/resources"
docker compose build
echo "    farm-server image built."

# ── 6. Start farm-server ─────────────────────────────────────────────────────
echo ""
echo ">>> Starting farm-server ..."
docker compose up -d
echo "    farm-server is starting. Waiting for health check ..."
sleep 10

# ── 7. Health check ──────────────────────────────────────────────────────────
echo ""
echo ">>> Running health check ..."
bash "${REPO_ROOT}/scripts/health-check.sh"

echo ""
echo "============================================"
echo " Host setup complete!"
echo "============================================"
