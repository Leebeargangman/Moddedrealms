// Loads config.json and the .env file once, so every other file can just `import { config } from "./config.js"`.
//
// Command-line flags override a few settings for quick tests:
//   --bots 3        number of bots (same as "botCount" in config.json)
//   --minutes 10    stop by itself after 10 minutes and write the session report
//   --mock          don't call the API at all: fake AI replies (free, for testing movement and logging)
//   --host / --port Minecraft server address

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// ES modules have no __dirname, so work it out from this file's URL.
export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const DATA_DIR = path.join(ROOT, "data");
fs.mkdirSync(DATA_DIR, { recursive: true });

// .env holds the secret API key: ANTHROPIC_API_KEY=sk-ant-...  (never commit it; it is in .gitignore)
// process.loadEnvFile is built into Node 20.12+, so no "dotenv" package is needed.
try {
  process.loadEnvFile(path.join(ROOT, ".env"));
} catch {
  // no .env file: fine in --mock mode, the AI client complains later otherwise
}

export const config = JSON.parse(fs.readFileSync(path.join(ROOT, "config.json"), "utf8"));

// --- command-line flags -------------------------------------------------------------------------
const args = process.argv.slice(2);
function flag(name) {
  const i = args.indexOf(`--${name}`);
  if (i < 0) return undefined;
  const next = args[i + 1];
  return next === undefined || next.startsWith("--") ? true : next;
}

export const options = {
  mock: Boolean(flag("mock")),
  minutes: flag("minutes") ? Number(flag("minutes")) : null,
};
if (flag("bots")) config.botCount = Number(flag("bots"));
if (flag("host")) config.server.host = String(flag("host"));
if (flag("port")) config.server.port = Number(flag("port"));

// Never ask for more bots than we have names for.
config.botCount = Math.max(1, Math.min(config.botCount, config.bots.length));
