# Claude civilisation (AI villagers)

Up to 20 Mineflayer bots live together on a Minecraft server and build a village. Each bot's "mind" is
**Claude Haiku 5.5** (`claude-haiku-5-5`). The whole design is about keeping API costs low and measured.

**No Claude key yet?** An OpenAI key works as a stand-in: put `OPENAI_API_KEY=sk-...` in `.env` and the
bots use **gpt-6-luna** (same price as Haiku 5.5). With `"provider": "auto"` (the default) the bots use
Claude as soon as `ANTHROPIC_API_KEY` is filled in, after a restart. Force one with `"provider"` in
`config.json` or `--provider openai`. An OpenAI account needs prepaid credit
(platform.openai.com → Billing) or every call fails with "insufficient quota".

> **Status: Phase 1** — one bot, the AI decision loop, the JSON action system, and token/cost logging.
> Coming next: Phase 2 (3 bots, task board, bot-to-bot chat, rate limiter, 30-minute test mode with a
> 20-bot projection), Phase 3 (20 bots, roles, village, progression, world log, leaders and laws,
> imposters, permadeath and newcomers: see "The game" below), Phase 4 (17:30–20:30
> UK schedule, saving/loading, dashboard on the website's Bots page, pm2, optional Sonnet leader),
> later Phase 5 (recording/streaming the bots).

## The game (design, built in Phase 3)

- **Goal:** the villagers build a civilisation together: village, homes, farms, roles, trade.
- **They govern themselves:** they choose their own leaders and write their own laws. The current
  leader and laws go into every bot's prompt, and elections and law changes are logged in the world log.
- **Two imposters** are hidden among the 20. Everyone knows two imposters exist, but not who they are.
  - Imposters act like normal villagers and make sure nobody suspects them.
  - Every few **Minecraft days** (a day is 20 minutes, so a 3-hour session has about 9) they secretly
    kill one or more villagers, only when nobody else is near enough to see it.
  - There is no built-in vote: what happens to suspects (trial, exile, punishment) is decided by the
    leader and the laws the villagers made.
- **Permadeath:** a villager who dies (or is exiled) never comes back. A completely new villager (new name,
  personality, role and memory) arrives at the village instead. At most 20 are alive at once.
- **Villagers can win:** a newcomer is always a normal villager, even when an imposter died, so once both
  imposters are caught the village is safe.
- **Real players can be dangerous.** Bots remember who attacked them and are careful around them.
- **Only witnesses know.** A villager learns about a death only if it is close enough to see it happen.
  Death messages are hidden (`/gamerule showDeathMessages false`) so the server doesn't announce the killer.
- **Deaths leave clues** that others can find later: the dropped items where the body fell, who was last seen
  near the victim and when, who was missing at the time, a villager who suddenly carries the victim's things.
  Villagers have to work out the rest themselves.

## How it keeps costs down

| Rule | Where in the code |
|---|---|
| **No timer-based thinking.** The AI is only asked when something happens: a task finished or failed, the bot was attacked, health/hunger got low, someone spoke to it, night fell / morning came, or it was idle for 2 minutes. Walking, mining, chopping, crafting, eating, fighting and fleeing are plain code. | `src/bot/civbot.js` (events and reflexes), `src/bot/skills.js` (actions) |
| **Cooldown:** at least 20 s between calls per bot. Events that arrive in between are batched into the next call. | `src/bot/brain.js` |
| **Short JSON output:** `max_tokens` 150, the reply is one small JSON object. Structured outputs make the API guarantee the JSON shape; we still validate every field and fall back to a safe default (never retry in a loop). Thinking is turned off. | `src/ai/actions.js`, `src/ai/client.js` |
| **Prompt caching:** the long, unchanging rules (~1,000 tokens) are a system prompt marked `cache_control`. It is identical for every bot, so all bots share one cache entry and pay 10% for it. | `src/ai/prompts.js` |
| **Small context:** a memory summary (rewritten every 10 decisions), the last 5 events and a compact state line. Never a chat history. A warning is logged if a request goes over 5,000 input tokens. | `src/bot/brain.js`, `src/bot/state.js` |
| **Budget cap:** $3.00/day (UK day). At 80% every cooldown doubles; at 100% bots stop calling the AI and use plain-code behaviour (or log off) until tomorrow. | `src/ai/client.js` |
| **Backoff:** on 429/529/overloaded/connection errors all AI calls pause, 5 s doubling up to 5 min. | `src/ai/client.js` |

Every call is logged to `data/usage.db` (SQLite) with time, bot, model, input / cache-read / cache-write /
output tokens and cost in USD, using the pricing table in `config.json`:

| Model | Input | Cache write (5 min) | Cache read | Output |
|---|---:|---:|---:|---:|
| claude-haiku-5-5 (prompts ≤ 100K) | $0.10 | $0.125 | $0.01 | $0.50 |
| claude-sonnet-5-5 | $2.00 | $2.50 | $0.10 | $10.00 |
| gpt-6-luna (OpenAI stand-in) | $0.10 | $0.125 | $0.01 | $0.50 |

(Per million tokens. Cache writes cost 1.25× input for the 5-minute cache.)

OpenAI caches automatically, but only prompts of 1,024 tokens or more. Our fixed rules are about that
size, so check the `r` (cache read) number in the first real run. Without caching a call costs roughly
$0.00013 instead of $0.00004, still far under the budget.

## Setup

1. **Node.js 22.13 or newer** (`node -v`). On Debian/Ubuntu: `curl -fsSL https://deb.nodesource.com/setup_22.x | sudo bash - && sudo apt install -y nodejs`.
2. **A Minecraft Java server in offline mode** (so bots don't need Microsoft accounts): `online-mode=false`
   in `server.properties`, and `max-players` above your bot count. Put its address, port and version in
   `config.json` → `server`. Our test server is **AITest201** (Paper 1.21.11, port 25576) on the VPS.
3. Install and add your key:
   ```bash
   cd ai-bot/civ
   npm ci
   cp .env.example .env        # then edit .env: ANTHROPIC_API_KEY=sk-ant-...  (or OPENAI_API_KEY=sk-...)
   ```
   `.env` is in `.gitignore`; the key is never logged.

## Run it

```bash
npm run mock          # free test: fake AI replies, 5 minutes (checks movement, events and logging)
npm run test-run      # Phase 1 test: real AI, 1 bot, stops after 10 minutes and writes the report
node src/index.js     # run until Ctrl+C
npm run report        # token/cost totals of the last sessions
```

Flags: `--bots N` (or `"botCount"` in config.json), `--minutes M`, `--mock`, `--host H`, `--port P`,
`--provider anthropic|openai`.

While running, a summary prints every 5 minutes (calls, tokens, cost today, average per call, per bot,
cache hit rate, projection for the session and for 20 bots). On exit the report is written to
`data/reports/session-<time>.md` (and `.json`). Bot memories are kept in `data/memory/<name>.json`.

### Reading the console

```
18:35:10 [Alder] decided craft oak_planks x4  [in 157+936r+0w out 13 $0.000032]
```
`in 157` = uncached input tokens, `936r` = read from the cache, `0w` = written to the cache,
`out 13` = output tokens, then the cost of that one call.

## Files

```
config.json           every setting: bot count, server, AI, pricing, budget, village, the 20 villagers
src/index.js          starts the bots (staggered), prints summaries, stops cleanly, writes the report
src/config.js         loads config.json, .env and command-line flags
src/ai/client.js      the only code that asks the AI: budget, backoff, usage logging
src/ai/providers/     anthropic.js (Claude, prompt caching) and openai.js (stand-in); same answers, same logging
src/ai/prompts.js     the cached system prompt and the memory prompt
src/ai/actions.js     the JSON action list, schema, validation and safe fallback
src/ai/mock.js        fake replies for --mock
src/bot/civbot.js     one villager: Mineflayer body, events, reflexes
src/bot/brain.js      when to call the AI (events, cooldown, batching) and what to send
src/bot/skills.js     how each action is done, in plain code
src/bot/state.js      the compact state summary
src/usage/ledger.js   SQLite log of every API call
src/usage/report.js   summaries, projections and the session report
```
