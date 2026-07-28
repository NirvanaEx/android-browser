#!/usr/bin/env bash
#
# Publish one browser account and the VPN profile that comes with it.
#
#   sudo bash tools/account-server-setup.sh <login> <password> [tunnel-ip]
#
# Run on the machine that already runs WireGuard. Afterwards the phone signs in
# with that login and password and gets its profile — no key ever has to be
# carried from the server to the phone by hand, which is the entire point.
#
# The password has no default, and no example of a real one appears anywhere in
# this repository, because the repository is public and that password is the
# only thing between a stranger who read it and a working peer on your server.
#
# Everything it does is additive and idempotent:
#
#   1. A key pair for the account under /root/wg-clients (kept if it exists).
#   2. That key added as a peer on the running wg0 — live, and appended to
#      /etc/wireguard/wg0.conf so it survives a reboot. Existing peers are not
#      touched and the config is backed up first.
#   3. The profile written as JSON under /srv/upgrid-api.
#   4. An nginx server block of its own on TCP 443, reusing the certificate that
#      is already on the box, serving that JSON behind HTTP basic auth. Nothing
#      else in the nginx configuration is read or changed.
#
# Adding a second account later is the same command with a different login, its
# own password and a free address.
#
# Removing one:  wg set wg0 peer <its public key> remove
#                (and delete its [Peer] block from /etc/wireguard/wg0.conf)
#
set -euo pipefail

LOGIN="${1:-superadmin}"
PASSWORD="${2:-}"
CLIENT_IP="${3:-10.7.0.4}"

if [ -z "$PASSWORD" ]; then
    cat >&2 <<'USAGE'
usage: sudo bash tools/account-server-setup.sh <login> <password> [tunnel-ip]

The password is required and is deliberately not defaulted here. This file is in
a public repository; a password written into it is a password anyone can read,
and this one hands out a WireGuard profile for your server. Pick one that isn't
worth guessing, then type it once in the browser — the phone remembers it.
USAGE
    exit 2
fi

# Adjust these two if the box ever changes. Everything else is derived.
HOST="${UPGRID_HOST:-ai-game.193-160-119-15.sslip.io}"
PUBLIC_IP="${UPGRID_PUBLIC_IP:-193.160.119.15}"

WG_IF="${UPGRID_WG_IF:-wg0}"
KEY_DIR=/root/wg-clients
API_DIR=/srv/upgrid-api
CERT_DIR="/etc/letsencrypt/live/$HOST"

# aaPanel keeps its vhosts and its nginx tree under /www; a stock install uses
# /etc/nginx. The password file goes next to the configuration either way —
# /etc/nginx does not even exist on an aaPanel box, which is what a hard-coded
# path found out the hard way, three steps into a run that had already touched
# WireGuard.
if [ -d /www/server/panel/vhost/nginx ]; then
    VHOST=/www/server/panel/vhost/nginx/upgrid-api.conf
    HTPASSWD=/www/server/nginx/conf/upgrid.htpasswd
else
    VHOST=/etc/nginx/conf.d/upgrid-api.conf
    HTPASSWD=/etc/nginx/upgrid.htpasswd
fi

[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }
command -v wg >/dev/null || { echo "wireguard-tools is missing" >&2; exit 1; }
wg show "$WG_IF" >/dev/null 2>&1 || { echo "$WG_IF is not up" >&2; exit 1; }
[ -f "$CERT_DIR/fullchain.pem" ] || { echo "no certificate at $CERT_DIR" >&2; exit 1; }

echo "==> account $LOGIN, tunnel address $CLIENT_IP"

# --- 1. keys ----------------------------------------------------------------
umask 077
mkdir -p "$KEY_DIR"
if [ ! -s "$KEY_DIR/$LOGIN.key" ]; then
    wg genkey > "$KEY_DIR/$LOGIN.key"
    echo "    generated a key pair"
else
    echo "    key pair already exists, keeping it"
fi
wg pubkey < "$KEY_DIR/$LOGIN.key" > "$KEY_DIR/$LOGIN.pub"
CLIENT_PRIV="$(cat "$KEY_DIR/$LOGIN.key")"
CLIENT_PUB="$(cat "$KEY_DIR/$LOGIN.pub")"

# --- 2. the peer ------------------------------------------------------------
wg set "$WG_IF" peer "$CLIENT_PUB" allowed-ips "$CLIENT_IP/32"
echo "    peer is live on $WG_IF"

if ! grep -q "$CLIENT_PUB" "/etc/wireguard/$WG_IF.conf"; then
    cp -a "/etc/wireguard/$WG_IF.conf" \
        "/etc/wireguard/$WG_IF.conf.bak-upgrid-$(date +%Y%m%d-%H%M%S)"
    {
        echo ""
        echo "# Upgrid Browser — $LOGIN, added $(date +%Y-%m-%d)"
        echo "[Peer]"
        echo "PublicKey = $CLIENT_PUB"
        echo "AllowedIPs = $CLIENT_IP/32"
    } >> "/etc/wireguard/$WG_IF.conf"
    echo "    peer written to /etc/wireguard/$WG_IF.conf"
else
    echo "    peer already in /etc/wireguard/$WG_IF.conf"
fi

# --- 3. the profile ---------------------------------------------------------
SERVER_PUB="$(wg show "$WG_IF" public-key)"
LISTEN_PORT="$(wg show "$WG_IF" listen-port)"

mkdir -p "$API_DIR"
# The umask above is 077, for the private key. nginx runs as its own user and
# cannot serve a file out of a directory it cannot even traverse — so this one
# directory is opened to nginx's group and to nobody else. The profiles inside
# stay 640 root:www.
chown root:www "$API_DIR" 2>/dev/null || chown root:www-data "$API_DIR" 2>/dev/null || true
chmod 0750 "$API_DIR"
CLIENT_PRIV="$CLIENT_PRIV" CLIENT_IP="$CLIENT_IP" SERVER_PUB="$SERVER_PUB" \
ENDPOINT="$PUBLIC_IP:$LISTEN_PORT" LOGIN="$LOGIN" \
python3 - > "$API_DIR/$LOGIN.json" <<'PY'
import json, os

config = "\n".join([
    "[Interface]",
    "PrivateKey = " + os.environ["CLIENT_PRIV"],
    "Address = " + os.environ["CLIENT_IP"] + "/32",
    "DNS = 1.1.1.1, 1.0.0.1",
    "MTU = 1420",
    "",
    "[Peer]",
    "PublicKey = " + os.environ["SERVER_PUB"],
    "Endpoint = " + os.environ["ENDPOINT"],
    "AllowedIPs = 0.0.0.0/0, ::/0",
    "PersistentKeepalive = 25",
    "",
])

print(json.dumps({
    "user": {"login": os.environ["LOGIN"], "name": os.environ["LOGIN"]},
    "vpn": {"config": config},
}, ensure_ascii=False, indent=2))
PY
chmod 640 "$API_DIR/$LOGIN.json"
chown root:www "$API_DIR/$LOGIN.json" 2>/dev/null \
    || chown root:www-data "$API_DIR/$LOGIN.json" 2>/dev/null || true
echo "    profile written to $API_DIR/$LOGIN.json"

# --- 4. the password --------------------------------------------------------
# apr1 rather than bcrypt: nginx accepts it everywhere and openssl is already
# installed, whereas htpasswd would mean pulling in apache2-utils.
HASH="$(openssl passwd -apr1 "$PASSWORD")"
touch "$HTPASSWD"
sed -i "/^$LOGIN:/d" "$HTPASSWD"
echo "$LOGIN:$HASH" >> "$HTPASSWD"
chmod 640 "$HTPASSWD"
chown root:www "$HTPASSWD" 2>/dev/null || chown root:www-data "$HTPASSWD" 2>/dev/null || true
echo "    password set"

# --- 5. nginx ---------------------------------------------------------------
# A server block of its own on TCP 443, which nothing was listening on —
# WireGuard's 443 is UDP, a different protocol, so there is no conflict. Same
# hostname and certificate as the site already on the box, so no new
# certificate has to be issued.
#
# No HTTP/2: the two ways of asking for it are split across nginx versions
# ("listen ... http2" up to 1.24, a separate "http2 on;" from 1.25) and the
# wrong one fails `nginx -t` outright. This serves one small JSON file a
# handful of times a day; HTTP/1.1 costs it nothing.
cat > "$VHOST" <<EOF
# Upgrid Browser account API — written by tools/account-server-setup.sh.
# Re-running that script overwrites this file.
server {
    listen 443 ssl;
    server_name $HOST;

    ssl_certificate     $CERT_DIR/fullchain.pem;
    ssl_certificate_key $CERT_DIR/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    # The profile. Basic auth IS the account: the browser sends the login and
    # password the user typed and gets their VPN config back.
    location /upgrid/ {
        auth_basic "Upgrid";
        auth_basic_user_file $HTPASSWD;
        alias $API_DIR/;
        default_type application/json;
        add_header Cache-Control "no-store";
    }

    location / { return 404; }
}
EOF

# Take our file back out if it doesn't pass. Leaving a rejected vhost on disk
# would break the *next* reload too — including one aaPanel does on its own,
# for a site that has nothing to do with this.
if ! nginx -t; then
    rm -f "$VHOST"
    echo "nginx rejected the new server block; it has been removed and nothing was reloaded." >&2
    echo "The WireGuard peer and the profile above are already in place." >&2
    exit 1
fi
nginx -s reload
echo "    nginx reloaded"

cat <<EOF

==> done

    profile   https://$HOST/upgrid/$LOGIN.json
    login     $LOGIN
    password  $PASSWORD
    tunnel    $CLIENT_IP  ->  $PUBLIC_IP:$LISTEN_PORT

    check it:
      curl -s -u '$LOGIN:$PASSWORD' https://$HOST/upgrid/$LOGIN.json | head -3

    then in the browser: menu -> the account row -> that login and password.
EOF
