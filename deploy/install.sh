#!/usr/bin/env bash
# One-command setup for a fresh Ubuntu/Debian VPS (no Docker needed).
#
# Installs Node.js and Caddy, runs the website as a systemd service in /opt/moddedrealms
# and gets a free HTTPS certificate for your domain.
#
# Usage, from the project folder:
#   sudo bash deploy/install.sh yourdomain.com [more-domains...] [discord-webhook-url]
#
# The first domain is the main address. Any extra domains (and every www. version)
# redirect to it, e.g.:  sudo bash deploy/install.sh moddedrealms.net modrealms.org
#
# Run it again any time to update the site after a git pull. Your .env and saved
# submissions are kept.
set -euo pipefail

APP_DIR=/opt/moddedrealms
APP_USER=moddedrealms
SERVICE=moddedrealms

say() { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Please run with sudo: sudo bash deploy/install.sh yourdomain.com"
command -v apt-get >/dev/null 2>&1 || die "This script supports Ubuntu/Debian. See README.md for other systems."

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$SRC_DIR/server/server.js" ] || die "Run this script from inside the Moddedrealms project folder."

# ---------- Domains ----------
normalize_domain() {
  local d="$1"
  d="${d#http://}"
  d="${d#https://}"
  d="${d%%/*}"
  d="${d#www.}"
  d="$(printf '%s' "$d" | tr '[:upper:]' '[:lower:]')"
  [[ "$d" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] \
    || die "\"$1\" doesn't look like a domain name."
  printf '%s' "$d"
}

DOMAIN=""
EXTRA_DOMAINS=()
WEBHOOK=""
for arg in "$@"; do
  if [[ "$arg" == https://*discord*/api/webhooks/* ]]; then
    WEBHOOK="$arg"
  elif [ -z "$DOMAIN" ]; then
    DOMAIN="$(normalize_domain "$arg")"
  else
    EXTRA_DOMAINS+=("$(normalize_domain "$arg")")
  fi
done
if [ -z "$DOMAIN" ]; then
  read -rp "Your domain (for example moddedrealms.net): " DOMAIN
  DOMAIN="$(normalize_domain "$DOMAIN")"
fi

# Every other address redirects to the main domain.
REDIRECTS="www.$DOMAIN"
for d in "${EXTRA_DOMAINS[@]}"; do
  [ "$d" = "$DOMAIN" ] || REDIRECTS="$REDIRECTS, $d, www.$d"
done

if [ -z "$WEBHOOK" ] && [ -t 0 ] && [ ! -f "$APP_DIR/.env" ]; then
  read -rp "Discord webhook URL for feedback & reports (optional, press Enter to skip): " WEBHOOK
fi

# ---------- Packages ----------
say "Installing system packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl ca-certificates gnupg rsync

node_major() {
  local v
  v="$(node -v 2>/dev/null || true)"
  v="${v#v}"
  echo "${v%%.*}"
}
if ! command -v node >/dev/null 2>&1 || [ "$(node_major)" -lt 18 ] 2>/dev/null; then
  say "Installing Node.js 22"
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
command -v node >/dev/null 2>&1 || die "Node.js installation failed."

if ! command -v caddy >/dev/null 2>&1; then
  say "Installing Caddy (automatic HTTPS)"
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key \
    | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt \
    > /etc/apt/sources.list.d/caddy-stable.list
  chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi

# ---------- App files ----------
say "Installing the website to $APP_DIR"
if ! id -u "$APP_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$APP_DIR" --no-create-home --shell /usr/sbin/nologin "$APP_USER"
fi
mkdir -p "$APP_DIR"
if [ "$SRC_DIR" != "$APP_DIR" ]; then
  rsync -a --delete --exclude .git --exclude .env --exclude data "$SRC_DIR"/ "$APP_DIR"/
fi
mkdir -p "$APP_DIR/data"
chown -R "$APP_USER:$APP_USER" "$APP_DIR/data"
chmod 700 "$APP_DIR/data"

ENV_FILE="$APP_DIR/.env"
set_env() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  awk -v k="$key" -v v="$value" '
    BEGIN { done = 0 }
    index($0, k "=") == 1 { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }
  ' "$ENV_FILE" > "$tmp"
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
}

if [ ! -f "$ENV_FILE" ]; then
  cp "$APP_DIR/.env.example" "$ENV_FILE"
fi
set_env DOMAIN "$DOMAIN"
set_env SITE_URL "https://$DOMAIN"
set_env HOST 127.0.0.1
set_env PORT 3000
set_env TRUST_PROXY true
if [ -n "$WEBHOOK" ]; then
  set_env DISCORD_WEBHOOK_URL "$WEBHOOK"
fi
if ! grep -q '^IP_HASH_SALT=.' "$ENV_FILE"; then
  set_env IP_HASH_SALT "$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
fi
chown "root:$APP_USER" "$ENV_FILE"
chmod 640 "$ENV_FILE"

# ---------- Service ----------
say "Starting the website service"
install -m 644 "$APP_DIR/deploy/moddedrealms.service" "/etc/systemd/system/$SERVICE.service"
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null
systemctl restart "$SERVICE"

for _ in $(seq 1 20); do
  if curl -fsS -o /dev/null http://127.0.0.1:3000/healthz; then break; fi
  sleep 0.5
done
curl -fsS -o /dev/null http://127.0.0.1:3000/healthz \
  || die "The website service didn't start. Check the logs with: journalctl -u $SERVICE -n 50"

# ---------- Caddy ----------
say "Configuring Caddy for $DOMAIN (redirecting $REDIRECTS)"
CADDYFILE=/etc/caddy/Caddyfile
NEW_CADDYFILE="$(mktemp)"
cat > "$NEW_CADDYFILE" <<EOF
# Moddedrealms website (written by deploy/install.sh)
$DOMAIN {
	encode zstd gzip
	reverse_proxy 127.0.0.1:3000
}

$REDIRECTS {
	redir https://$DOMAIN{uri} permanent
}
EOF
if [ -f "$CADDYFILE" ] && ! cmp -s "$CADDYFILE" "$NEW_CADDYFILE"; then
  BACKUP="$CADDYFILE.bak.$(date +%Y%m%d%H%M%S)"
  cp "$CADDYFILE" "$BACKUP"
  echo "Saved your previous Caddyfile to $BACKUP"
fi
caddy validate --config "$NEW_CADDYFILE" --adapter caddyfile >/dev/null
install -m 644 "$NEW_CADDYFILE" "$CADDYFILE"
rm -f "$NEW_CADDYFILE"
systemctl enable caddy >/dev/null
systemctl reload caddy 2>/dev/null || systemctl restart caddy

# ---------- Firewall ----------
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  say "Opening ports 80 and 443 in ufw"
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw allow 443/udp >/dev/null
fi

# ---------- Done ----------
PUBLIC_IP="$(curl -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || true)"
PUBLIC_IP="${PUBLIC_IP:-YOUR_SERVER_IP}"

say "All done!"
cat <<EOF

  Your website is running. Last step: point your domain at this server.
  For EACH domain ($DOMAIN${EXTRA_DOMAINS[*]:+ ${EXTRA_DOMAINS[*]}}), add these DNS records
  at your registrar (or Cloudflare):

      Type  Name  Value
      A     @     $PUBLIC_IP
      A     www   $PUBLIC_IP

  Once DNS updates (usually a few minutes), open https://$DOMAIN
  Caddy gets the HTTPS certificate automatically.

  Settings:    $ENV_FILE  (then: sudo systemctl restart $SERVICE)
  Submissions: $APP_DIR/data/
  Logs:        journalctl -u $SERVICE -f
EOF
if ! grep -q '^DISCORD_WEBHOOK_URL=.' "$ENV_FILE"; then
  echo
  warn "No Discord webhook set. Feedback and reports will only be saved in $APP_DIR/data/."
  echo "  Add DISCORD_WEBHOOK_URL to $ENV_FILE and restart the service to get them in Discord."
fi
