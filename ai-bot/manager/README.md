# 2b2t AI survival bot (TheDrVillager)

A Minecraft bot that sits in the 2b2t queue on your VPS, and once it's in the game tries to survive and grow
stronger on its own. A local LLM (via [Ollama](https://ollama.com)) plans what to do. Hard-coded reflexes
handle anything that needs a fast reaction.

```
           ┌──────────── every task ────────────┐
 state ──► │ Brain: local LLM (Ollama)          │ ──► one action: travel_far, gather_wood, craft,
           │   + rule-based planner (fallback   │     mine, smelt, hunt, sleep, hide, strip_mine,
           │     and hint to the LLM)           │     stash, withdraw, goto, …
           └────────────────────────────────────┘
 every 250 ms: Reflexes (can interrupt any action)
   totem in off-hand · flee players · fight/flee mobs · eat · best armor · drowning/lava · panic-disconnect
```

## What it does

1. **Queue**: connects to `connect.2b2t.org` (or any server, e.g. `9b9t.com`) and reads your queue position from chat, the action bar and the
   tab-list header. It logs the position and ETA and optionally posts them to Discord. It reconnects on its own
   with backoff.
2. **Gets away from spawn**: walks a secret bearing that stays 15–30° off every highway (the axes and the
   diagonals) until it is `settleDistance` blocks out (15,000 by default). On the way it grabs wood or food when
   they are right there.
3. **Progression**: wood tools → stone tools → food (hunting and cooking) → bed (sets its respawn point far
   from spawn) → strip-mining at y = -53 for iron and diamonds → iron gear → diamond gear → a hidden stash chest.
4. **Survival reflexes**: keeps a totem in the off-hand, backs away from strangers and runs from hostile
   players (see below), fights mobs (and runs from creepers, wardens and ghasts), eats, wears its best armor, and hides
   underground at night.
5. **Panic disconnect**: if health is ≤ 3 hearts with a player close by, and the bot carries enough valuable
   gear, it logs out rather than lose everything. The downside is sitting through the queue again.
6. **Memory** (`data/memory.json`): travel bearing, stash location, bed spawn, deaths, notes such as
   "diamonds at x y z, need iron pickaxe", and recent events. This survives restarts and deaths.

If Ollama is down, slow, or returns nonsense, the bot uses the rule-based planner, so it never stands still.

## Owner commands and chat

**DrLeebear** is the owner (`social.owner`). Whisper the bot to control it:

```
/msg TheDrVillager status        position, health, food, what it's doing (or queue position)
/msg TheDrVillager inv           inventory
/msg TheDrVillager come          walk to you            follow   follow you for 10 min
/msg TheDrVillager goto 1200 -3400                      give diamond 5   walk over and throw it to you
/msg TheDrVillager stop          stop and wait for orders       auto   go back to doing its own thing
/msg TheDrVillager <anything>    the AI interprets it: a one-off job ("go get some wood"), or a standing
                                 order it keeps following ("from now on focus on iron gear")
/msg TheDrVillager forget orders clear the standing order
```

When it finishes or fails an order, it whispers you the result. Only your whispers count as orders. If the
server you play on lets anyone use any name (cracked servers), set `social.ownerSecret` and start every
command with it.

**Chat rules**
- It never talks on its own, and it ignores almost all chat. The AI only ever sees three kinds of message:
  your whispers, messages that contain its name (`TheDrVillager` / `drvillager`), and messages from players
  within 32 blocks. Everything else is dropped before the AI sees it.
- **Strangers who come close**: it backs off to about 12 blocks and may whisper something friendly. It plays a
  harmless, broke newbie so it isn't worth killing. It never gives out coordinates, its base or stash, its items,
  or your name. Any reply with a 3+ digit number or your name in it is blocked before sending, and it never sends
  commands.
- **Trust**: every player gets a trust score that is saved to disk. It goes up slowly while they're near
  without attacking, and goes up or down depending on how they talk.
  - Trusted players (40+) are left alone.
  - Anyone who hits it or makes threats becomes **hostile** for good. It runs from them at 24+ blocks, never
    talks to them again, and fights back if cornered.
  - It never throws the first punch at a stranger.
- It won't open its stash while a non-friend is within 64 blocks.
- It replies by whisper. It only answers in public when someone far away says its name. Public replies are
  limited to one per 45 s, and it sends at most 4 messages a minute overall.

## Setup on the VPS

Copy this folder to the VPS (without `node_modules`), then:

```bash
bash deploy/install.sh
```

This installs Node 22 and Ollama, picks a model sized for the VPS, writes `config.json` and installs a systemd
service.

| VPS                        | Model          | Notes                                    |
|----------------------------|----------------|------------------------------------------|
| GPU, 16 GB+                | `qwen2.5:14b`  | ~5 s per decision                         |
| CPU only, 16 GB, 8+ cores  | `qwen2.5:7b`   | ~20–60 s per decision                     |
| CPU only, 6–15 GB          | `qwen2.5:3b`   | weaker planning; the rules cover the gaps |
| < 6 GB                     | none           | rule-based brain only                     |

### Sign in (once)

```bash
npm run login
```

This prints a code. Open `https://www.microsoft.com/link` on any device, enter the code and sign in with the
Microsoft account that owns **TheDrVillager**. Tokens are cached in `auth-cache/` (keep that folder private).
The bot never sees your password. If the token expires later, the sign-in code also shows up in the log and in
Discord.

### Run

```bash
sudo systemctl enable --now 2b2t-bot
```

To watch the live log:

```bash
journalctl -fu 2b2t-bot
```

For a one-screen summary (queue position, position, health, current plan):

```bash
npm run status
```

## Config (`config.json`)

| Key | Default | Meaning |
|---|---|---|
| `server.version` | `false` | Auto-detect. If the connection fails, set it to 2b2t's current version, e.g. `"1.21.4"` |
| `ai.model` | `qwen2.5:7b` | Any Ollama model. Small instruct models with JSON output work best |
| `ai.enabled` | `true` | `false` = rule-based brain only |
| `behavior.settleDistance` | `15000` | How far from spawn before building anything |
| `behavior.friendlyPlayers` | `[]` | Players it won't run from |
| `behavior.fleePlayerRadius` | `24` | Run from hostile players closer than this |
| `behavior.carefulRadius` | `12` | Back away from unknown players closer than this |
| `behavior.killCommand` | `/kill` | Used to respawn when stranded high up with nothing to lose (`""` to disable) |
| `behavior.panicDisconnect` | `true` | Log out when about to die with good gear |
| `social.owner` | `DrLeebear` | Whose whispers are orders |
| `social.ownerSecret` | `""` | Optional prefix every owner command must start with |
| `social.aliases` | `["drvillager"]` | Other names that count as "talking to me" |
| `social.whisperCommand` | `msg` | Command used to whisper (`msg`, `w`, `tell`...) |
| `social.greetStrangers` | `true` | Might whisper a stranger who walks up |
| `notify.discordWebhook` | `""` | Discord webhook URL for queue, join, death and disconnect alerts |

## Testing locally

Point it at any offline-mode server:

```bash
MC_HOST=127.0.0.1 MC_PORT=25565 MC_AUTH=offline MC_USERNAME=TestBot SETTLE_DISTANCE=60 npm start
```

## Honest expectations

- **Queue**: without priority queue, 2b2t's queue can take hours. The bot just waits. Each disconnect (server
  restart, panic log) puts it back in line.
- **2b2t is brutal.** Spawn is a wasteland of holes, lava and spawn-killers, and there is very little food for
  thousands of blocks. The bot will die, possibly a lot, before it gets far enough out. After each death it
  picks a new bearing. A bed spawn far from spawn is the big milestone.
- **What it can't do**: no elytra flight, no nether highways, no crystal PvP, no ender chests or shulker
  logistics. Against a skilled player with a hacked client, running away is the only real defence, so that is
  what it does.
- Mineflayer movement is legit (no fly or speed hacks), so 2b2t's anticheat shouldn't flag it. Rubber-banding
  can still make pathing stutter.

## Files

```
src/index.js      connection loop, reconnect, death/respawn handling
src/queue.js      2b2t queue detection + ETA
src/agent.js      task runner (priorities, interrupts, resume), status file
src/reflexes.js   fast survival behaviour
src/brain.js      LLM planner (Ollama, JSON-schema output) + rule-based planner
src/social.js     owner commands, chat filter, per-player trust, safe replies
src/llm.js        Ollama client
src/skills.js     everything the bot can do
src/combat.js     fight / flee / armor / totem / gear score
src/state.js      world snapshot fed to the planner
src/memory.js     persistent memory
src/login.js      one-off Microsoft sign-in
deploy/install.sh VPS setup + systemd unit
```
