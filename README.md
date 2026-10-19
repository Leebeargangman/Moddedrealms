# Moddedrealms Website

The official website for **Moddedrealms** (Modrealms for short), the Minecraft server built around teamwork and strategy.

- **Homepage**: hero with copy-IP button and live server status, Battles gamemodes (WW1, WW2, Modern and more), the Ultimate Vehicle Experience gallery, latest YouTube videos, how to join, and community links.
- **Feedback & Reports page**: a feedback form and a report-a-player form. Submissions go to a Discord channel through a webhook, and a copy is saved on the server.
- **Live data**: player count from Minehut, Discord member and online counts, and your latest YouTube uploads. These update automatically.
- **Short links**: `yourdomain.com/discord`, `/youtube`, `/modrinth`, `/feedback`, `/report`.
- No build step and no npm packages. It's plain HTML, CSS and JS plus a small Node.js server.

```
website/            The site itself (HTML, CSS, JS, fonts, images)
  index.html        Homepage
  support.html      Feedback & Reports page
  image/            Your original images
  image/web/        Web-optimized copies used by the site (WebP)
server/server.js    Serves the site and handles the forms (Node.js 18+)
deploy/             Caddyfile, systemd service and the one-command installer
docker-compose.yml  Website + Caddy (automatic HTTPS) in Docker
.env.example        Settings template
```

---

## Preview it on your computer

Install [Node.js](https://nodejs.org) 18 or newer, then from this folder run:

```bash
node server/server.js
```

Open http://localhost:3000. Forms and live status work locally too (submissions are saved to `data/`).

You can also double-click `website/index.html` to look at the design, but the forms and live status only work through the server.

---

## Put it on your VPS and link your domain

You need:

- A VPS running Linux (Ubuntu 22.04/24.04 or Debian 12 recommended) with ports **80** and **443** open. Some providers have a firewall in their control panel; open both ports there.
- A domain name (from Namecheap, Cloudflare, Porkbun, GoDaddy, etc.).

### Step 1: Point your domain at the VPS

In your domain registrar's DNS settings, add two records (replace `203.0.113.10` with your VPS's IP address):

| Type | Name  | Value          |
|------|-------|----------------|
| A    | `@`   | `203.0.113.10` |
| A    | `www` | `203.0.113.10` |

DNS changes usually take a few minutes but can take up to a few hours.

> **Using Cloudflare?** Set both records to **DNS only** (grey cloud) for the first setup so the HTTPS certificate can be issued. Afterwards you can switch the proxy on (orange cloud) with SSL/TLS mode set to **Full (strict)**.

### Step 2: Get the code onto the VPS

SSH into your VPS and clone the repository:

```bash
git clone https://github.com/leebeargangman/moddedrealms.git
cd moddedrealms
```

If the repository is private, GitHub asks you to sign in. Use your GitHub username and a [personal access token](https://github.com/settings/tokens) as the password. You can also upload the folder with an SFTP app like FileZilla or WinSCP.

### Step 3: Start the site (pick ONE option)

#### Option A: Docker (recommended)

```bash
# Install Docker if you don't have it yet
curl -fsSL https://get.docker.com | sudo sh

# Settings: set DOMAIN to your domain, and add your Discord webhook (see below)
cp .env.example .env
nano .env

# Start everything
sudo docker compose up -d --build
```

That's it. Visit `https://yourdomain.com`. [Caddy](https://caddyserver.com) fetches and renews the HTTPS certificate automatically, and `www.yourdomain.com` redirects to `yourdomain.com`.

#### Option B: One-command install (Ubuntu/Debian, no Docker)

```bash
sudo bash deploy/install.sh yourdomain.com
```

This installs Node.js and Caddy, runs the site as a background service that starts on boot, and sets up HTTPS. It asks for your Discord webhook (you can skip it and add it later). Settings live in `/opt/moddedrealms/.env`.

#### Option C: You already run nginx or Apache

Run the Node server as a service (see `deploy/moddedrealms.service`, with `HOST=127.0.0.1` and `TRUST_PROXY=true` in `.env`) and proxy your domain to it. For nginx:

```nginx
server {
    server_name yourdomain.com www.yourdomain.com;
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Then get a certificate with `sudo certbot --nginx -d yourdomain.com -d www.yourdomain.com`.

---

## Get feedback and reports in Discord

1. In Discord, open your server's **Server Settings → Integrations → Webhooks → New Webhook**.
2. Pick the channel (for example a private `#staff-reports` channel), then click **Copy Webhook URL**.
3. Put it in `.env`:
   ```
   DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/...
   ```
4. Restart the site:
   - Docker: `sudo docker compose up -d`
   - Install script: `sudo systemctl restart moddedrealms`

Optional settings:

- `DISCORD_FEEDBACK_WEBHOOK_URL` and `DISCORD_REPORT_WEBHOOK_URL` send feedback and reports to different channels.
- `DISCORD_REPORT_PING_ROLE_ID` @mentions a staff role on every new report. Turn on Developer Mode in Discord, then right-click the role and choose **Copy Role ID**.

Keep the webhook URL private, since anyone who has it can post in that channel. It lives only in `.env`, which is never committed to git.

### Where submissions are saved

Every submission is also saved on the server, one JSON object per line, even if Discord is down or no webhook is set:

- Docker: `sudo docker compose exec web cat data/reports.jsonl` (or `data/feedback.jsonl`)
- Install script: `/opt/moddedrealms/data/`

Each submission gets a reference code (like `R-7K3F2A`) that the player sees, so staff can match a player's message to their report.

### Spam protection

- Each visitor can send at most 5 submissions every 15 minutes (`SUBMIT_RATE_LIMIT`).
- A hidden "honeypot" field catches simple bots.
- Submissions sent faster than a person could type are saved with a `flagged` note but not posted to Discord.
- User text can never ping `@everyone`, `@here`, users or roles.
- IP addresses are not stored. Only a salted hash is kept to spot repeat spammers.

---

## Updating the site

After you change files (or pull new changes):

```bash
cd moddedrealms
git pull
sudo docker compose up -d --build     # Docker
# or
sudo bash deploy/install.sh yourdomain.com   # install script
```

Your `.env` settings and saved submissions are kept.

## Editing content

- **Text**: edit `website/index.html` and `website/support.html`. Each homepage section is marked with a comment like `<!-- ============ BATTLES ============ -->`.
- **Server IP, Discord, YouTube and Modrinth links**: search for `moddedrealms.minehut.gg`, `discord.gg/AA3a3H3ECk`, `@Moddedrealms` and `modrinth.com` in the HTML files. If you change the IP, also update `MC_SERVER_ADDRESS` in `.env` so the live player count keeps working.
- **Colors and fonts**: the variables at the top of `website/css/style.css` (`--green`, `--yellow`, and so on).
- **Images**: add new images to `website/image/` and reference them from the HTML. For fast loading, convert large screenshots to WebP around 1600px wide (the site's versions are in `website/image/web/`).
- **Videos**: the "Latest videos" section updates itself from your YouTube channel. The featured "Trench warfare" video is set in `index.html`.

## Settings reference (`.env`)

| Setting | What it does |
|---|---|
| `DOMAIN` | Your domain, e.g. `moddedrealms.net` (Docker uses it for HTTPS). |
| `SITE_URL` | Full site URL, used for Discord/Twitter link previews. Docker sets it from `DOMAIN`. |
| `DISCORD_WEBHOOK_URL` | Where feedback and reports are posted. |
| `DISCORD_FEEDBACK_WEBHOOK_URL` / `DISCORD_REPORT_WEBHOOK_URL` | Optional separate channels. |
| `DISCORD_REPORT_PING_ROLE_ID` | Optional role to @mention on new reports. |
| `PORT` | Port the Node server listens on (default `3000`). |
| `HOST` | Address to listen on. Use `127.0.0.1` when a proxy runs on the same machine. |
| `TRUST_PROXY` | `true` when running behind Caddy or nginx, so visitor IPs are read correctly. |
| `SUBMIT_RATE_LIMIT` | Max submissions per visitor per 15 minutes (default `5`). |
| `MC_SERVER_ADDRESS` | Minecraft server used for the live player count. |
| `DISCORD_INVITE_CODE` | Invite used for the live member count. |
| `YOUTUBE_CHANNEL_ID` | Channel used for the latest videos. |
| `DATA_DIR` | Where submissions are saved (default `data/`). |

## Troubleshooting

- **The site doesn't load or HTTPS fails**: check that DNS points at your VPS (`ping yourdomain.com` should show your VPS IP) and that ports 80 and 443 are open in your provider's firewall. Then check the logs:
  - Docker: `sudo docker compose logs -f`
  - Install script: `journalctl -u moddedrealms -f` and `journalctl -u caddy -f`
- **Forms say "Couldn't reach the server"**: the site is being served without the Node server (for example as plain files). Run it with one of the options above.
- **Discord messages don't arrive**: check the webhook URL in `.env`, restart, and look for `[discord]` lines in the logs. Submissions are still saved in `data/`.

---

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
