// Loads config.json and the .env file once, so every other file can just `import { config } from "./config.js"`.
//
// Command-line flags override a few settings for quick tests:
//   --bots 3        number of bots (same as "botCount" in config.json)
//   --minutes 10    stop by itself after 10 minutes and write the session report
//   --mock          don't call the API at all: fake AI replies (free, for testing movement and logging)
//   --host / --port Minecraft server address
//   --provider X    "anthropic" or "openai" (overrides ai.provider)

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// ES modules have no __dirname, so work it out from this file's URL.
export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const DATA_DIR = path.join(ROOT, "data");
fs.mkdirSync(DATA_DIR, { recursive: true });

// .env holds the secret API key: ANTHROPIC_API_KEY=sk-ant-... and/or OPENAI_API_KEY=sk-...
// (never commit it; it is in .gitignore)
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
  scheduled: Boolean(flag("scheduled")),
  mock: Boolean(flag("mock")),
  minutes: flag("minutes") ? Number(flag("minutes")) : null,
};
if (flag("bots")) config.botCount = Number(flag("bots"));
if (flag("host")) config.server.host = String(flag("host"));
if (flag("port")) config.server.port = Number(flag("port"));

// Never ask for more bots than we have names for.
config.botCount = Math.max(1, Math.min(config.botCount, config.bots.length));

// --- which AI provider to use -------------------------------------------------------------------
// "auto" = Claude if ANTHROPIC_API_KEY is set, otherwise OpenAI if OPENAI_API_KEY is set.
// So adding a Claude key later switches everything over by itself (after a restart).
const wanted = String(flag("provider") || config.ai.provider || "auto").toLowerCase();
export const provider = wanted !== "auto" ? wanted
  : process.env.ANTHROPIC_API_KEY ? "anthropic"
  : process.env.OPENAI_API_KEY ? "openai"
  : "anthropic";
if (!["anthropic", "openai"].includes(provider)) throw new Error(`config ai.provider must be "auto", "anthropic" or "openai" (got "${wanted}")`);
/** The model id the bots use, e.g. claude-haiku-5-5 or gpt-6-luna. */
export const model = options.mock ? "mock" : provider === "openai" ? config.ai.openaiModel : config.ai.model;
