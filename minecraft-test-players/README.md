# Moddedrealms Test Players

A Paper 1.21.11 plugin for performance testing: admins spawn **simulated test players** that walk around the
server, keep the chunks around them loaded and ticking like real players, and can be driven by an AI model.
They show up on the website's **Bots** page, where staff can create, stop and talk to them like the AI bots.

Test players are mannequins (the player-shaped entity added in Minecraft 1.21.9) that the server spawns
itself. Nothing logs in: they have no Minecraft account, don't take a player slot and exist only on this server.

## Install

1. Put `ModdedrealmsTestPlayers-1.1.0.jar` in the server's `plugins` folder. It needs **Paper** (or a Paper
   fork) 1.21.11 or newer; it also compiles cleanly against Paper 26.1 and 26.3. Spigot doesn't have the
   mannequin API. On Minehut, upload it with the file manager.
2. Restart the server once. This creates `plugins/ModdedrealmsTestPlayers/config.yml`.
3. Optional: fill in the `website` section (below) and run `/testplayer reload`.

## Commands

Everything is `/testplayer` (or `/tplayer`), for ops or anyone with `moddedrealms.testplayers.admin`.
A `<target>` is one name, a prefix ending in `*` (`Load*`) or `all`.

| Command | What it does |
|---|---|
| `spawn <name> [count] [idle\|wander\|walk]` | Spawn one test player, or a numbered batch (`spawn Load 50 wander` makes Load1 to Load50) spread around you |
| `despawn <target>` / `start <target>` | Take out of the world / put back where they were |
| `remove <target>` | Forget them completely |
| `move <target> <x y z \| here \| player>` | Walk somewhere (it finds a route: up steps, around cliffs and water) |
| `follow <target> <player>` | Follow a player |
| `wander <target> [radius]` | Walk around randomly near where they are |
| `walk <target> [north\|south\|...\|yaw]` | Walk straight on, generating new chunks (turns when blocked) |
| `stop <target>` | Stand still, AI paused |
| `sprint <target> <on\|off>` | |
| `bring <target>` / `tp <name>` | Teleport them to you / you to it |
| `say <name> <text>` | Chat as the test player (always shown with the `[Test]` prefix) |
| `ai <target> <model\|on\|off> [staff]` | Let an AI model control them (see below) |
| `goal <target> <text\|clear>` | A goal their AI works toward |
| `personality <target> <preset\|random\|text\|clear>` | Who they are (see below) |
| `build <target> <structure> [wood\|spruce\|stone\|brick\|cherry]` | Build next to them (inside a build zone): hut, house, townhall, tower, wall, campfire, farm, well, lamp or stall |
| `undo <target>` | Remove everything they built |
| `zone add <name> <radius>` / `zone add <name> <x1> <z1> <x2> <z2>` / `zone list` / `zone remove <name>` | Where test players may build |
| `town found <name> [radius]` / `town found <name> <radius> <x> <z>` | Plan a town around you (also makes it a build zone) |
| `town join <town> <target> [job]` / `town leave <target>` | Make test players citizens (job from their personality) |
| `town info [name]` / `town list` / `town remove <name>` | How the towns are doing (storage chest, mine, known portals) |
| `town portal <town>` | Tell a town's explorers about the nether portal you're standing next to |
| `api [on\|off]` | The paid AI API's budget and what's been used; `off` pauses it |
| `job <target> <job\|auto>` | Change a citizen's job |
| `list`, `info <name>`, `stats` | What they're doing and carrying; TPS, MSPT, chunks, entities, memory, AI budget |
| `reload` | Re-read config.yml |

A typical load test: `spawn Load 100 wander`, `spawn Far 20 walk`, then watch `/testplayer stats` (or spark).

## AI models

`/testplayer ai Load1 on` lets an AI decide what Load1 does: chop a tree, go mining, head off exploring, take a
trip to the nether, hang out at the campfire, follow a player, build. It's told what it carries, what its town
has and who's around, and players who mention its name in chat get an answer. Without an AI everything still
happens (jobs, trips, trading); the AI adds choices and conversation. Where it thinks (`ai.provider`):

| Provider | What it costs | Notes |
|---|---|---|
| `ollama` | free | An Ollama this server can reach (`ai.ollama-url`). Not available on Minehut. |
| staff connector | free | `/testplayer ai Load1 on DrLeebear` runs it on that staff member's PC through the website (their connector running, website link on). Works on Minehut. |
| `openai` | your API key | Any OpenAI-style API: OpenAI, OpenRouter, Groq, Together... Set `ai.model` and `ai.api.base-url`. |
| `anthropic` | your API key | Claude. `ai.model` defaults to `claude-haiku-5-5`. |

### API keys without surprise bills

Put the key in `ai.api.key` (keep `config.yml` private). It's never shown in game, on the website or in logs,
and error messages have it masked. Spending has hard caps that can't be exceeded:

- `max-requests-per-hour` (120) and `max-tokens-per-day` (200,000), counted from what the API reports and saved
  across restarts in `ai-usage.yml`
- `min-seconds-per-player` (60): each test player asks at most once a minute
- `max-players` (4): only that many test players use the API, the rest run on their own routines
- `max-answer-tokens` (220): every answer is short
- optional `max-dollars-per-day` with your API's prices per million tokens

When a cap is reached the test players carry on without the AI until the hour or day rolls over.
`/testplayer api` shows what's been used; `/testplayer api off` pauses it straight away. For a firm money
limit, put your provider's prices in and set `max-dollars-per-day`.

`ai.max-concurrent` (default 2) caps how many model requests run at once, so the AI doesn't slow down the
test itself.

## Personalities

`/testplayer personality Ann joker` gives an AI test player a personality that shapes what it does and how it
talks. Presets: `builder`, `explorer`, `leader`, `joker`, `nervous`, `grumpy`, `friendly`; `random` picks one
for each (`/testplayer personality Villager* random`); anything else is used as written ("a pirate who only
talks about treasure"). AI test players within 32 blocks hear each other and talk back, at most once every
30 seconds each, so conversations keep going without flooding the chat.

## Building

Test players build small structures block by block (arm swinging, place sounds, about 3 blocks a second):
a hut, a house, a tower, a wall or a campfire, in wood, spruce, stone, brick or cherry. AI test players choose
to build on their own, and see what the others have built nearby, so a group with a shared goal grows a little
village.

Safety, because this can run on a live server:

- They only build inside **build zones**: `/testplayer zone add village 60` makes a 121 x 121 square around
  you. No zones = no building.
- They never replace a block that's already there, and never place a block where a player or mob stands.
- `/testplayer undo <target>` removes everything a test player built (blocks someone changed since are left).
- `building.max-builds-per-player` (6) and `building.cooldown-seconds` (120) stop them covering the zone.

They pick a spot that's level within two blocks (filling in a foundation), near their own height first. Put
zones on fairly flat ground: on steep hills they may not find a spot, and if they can't walk to one they hop.

## Survival: nothing from nowhere

With `survival.enabled` (the default) test players play survival like everyone else:

- **Everything they place comes from their inventory.** Planks are crafted from logs they chopped, stone
  bricks from cobblestone they mined, torches from coal and sticks, a bucket from iron they smelted. With no
  stone they build in wood; with no glass a window stays open, like early-game players.
- **Breaking takes time.** They walk up, swing, the block cracks (players nearby see the cracks) for as long
  as it takes with the tool in hand, and it drops what it really drops. They craft better tools as they go
  (wood, then stone, then iron), and hold the right one.
- **Trees**: only natural ones (leaves a player placed are never mistaken for a tree). They break leaves in
  their way, jump-and-place dirt to reach the top of tall trees and take it back after, and replant a
  sapling outside town.
- **Out of wood mid-build**, a builder fetches logs from the town's storage chest or goes and chops a tree,
  then carries on.
- **Pathfinding**: they find a route over the blocks (up steps, down drops, around cliffs, out of lakes).

`survival.admin-builds-free: true` lets `/testplayer build` orders skip materials; `survival.enabled: false`
brings back the old instant building.

## Trading

Right-click a test player to trade, like a villager, with what it really carries: it sells what it has
plenty of for emeralds (about an emerald's worth per trade), buys what it needs (iron, coal, bread, sand,
logs) with emeralds it has earned, and swaps straight across when it has none. Each trade moves real items in
and out of its inventory. Builders keep the wood they need and nobody sells their tools. Turn it off with
`survival.trading: false`.

## Towns: a civilisation that builds itself

`/testplayer town found Riverton 45` plans a whole town around you, in a 91 x 91 square: a town square with a
campfire and a well, a stone town hall with a bell, about 20 houses and huts in mixed materials, market stalls,
four fenced wheat farms, lamp posts and guard towers. `/testplayer town join Riverton Citizen*` makes test
players its citizens, and they build it, in that order, with dirt roads from every door to the square. It grows
from a **camp** to a **village** (2 homes), a **town** (6 homes and the town hall) and a **city** (12 homes,
3 towers), and the mayor announces each step in chat.

Every citizen has a job, from its personality (or `/testplayer job <name> <job>`):

| Personality | Job | What it does |
|---|---|---|
| builder (or none) | builder | builds the plan in order with materials it gathers or takes from storage, paves roads |
| builder (3rd one) | lumberjack | chops trees in and around town (clearing plots), fills the storage chest with logs |
| builder (4th one) | miner | digs the town mine: staircases down to y=16, then tunnels; brings up stone, coal and ore |
| leader | mayor | builds the campfire, well and town hall, then looks over the town and announces how it's doing |
| nervous | farmer | builds the farms, harvests ripe wheat (keeping the wheat and seeds), bakes bread |
| friendly | trader | builds the market stalls, minds one and greets players who come by |
| grumpy | guard | builds the guard towers, patrols the town (also at night), goes along with the scout on trips (into the nether too) |
| explorer | scout | explores 120-360 blocks out, notes nether portals, takes trips through them to mine quartz and glowstone, comes back to report |
| joker | entertainer | hangs around the campfire telling jokes when there's an audience |
| (by hand) | roadworker | paves the roads and puts up lamp posts |

Gatherers put what they collect in the town's **storage chest** by the campfire (the first one with the wood
makes it), and builders take what they need from it. At dusk everyone meets at the campfire; at night everyone
except the guards goes home, into a house nobody else lives in, or to the campfire. Trees on a building plot
get chopped down first (a builder or the lumberjack clears it, and keeps the wood).

**Nether trips**: scouts look for nether portals while exploring (or add one with `/testplayer town portal
<town>` standing next to it). Half their trips then go through it, with a guard: they walk in, the game takes
them to the nether (and makes a way back like it does for players), they mine quartz, gold and glowstone for a
few minutes and come back through. Players nearby just see them go through the portal. Turn it off with
`survival.nether-trips: false`.

None of this needs an AI model; with one, citizens pick some of it themselves (a trip, a break by the
campfire) and talk about their town and their work in character.

Building is still safe for a live server: the town is a build zone, nothing is built over existing blocks
(roads and farms only replace grass and dirt), and `/testplayer undo <citizens>` takes it all away again.
A building that doesn't fit where it's planned (a hill, a lake) is nudged a few blocks or skipped. Put towns
on fairly flat ground. With survival on, towns grow at the pace of real players: in a forest, 8 citizens had
13 buildings (all from wood they chopped) after about 25 minutes. With `survival.enabled: false` a town of 7
reached "town" in about 6 minutes.

A quick civilisation:

```
/testplayer town found Riverton 45
/testplayer spawn Citizen 8 idle
/testplayer personality Citizen* random
/testplayer town join Riverton Citizen*
/testplayer ai Citizen* on DrLeebear      (optional: they choose and talk; or an API key)
```

## Running it on Minehut (play.moddedrealms.net)

Test players are spawned by the server, so Minehut's proxy, online mode and player slots don't matter (they
don't show in the player count). Minehut has no Ollama, so for an AI either put an API key in `ai.api` (see
"API keys without surprise bills") or run the AI on your PC through the website, for free:

1. In `config.yml` set `website.enabled: true`, `website.manager-key` (the website's `BOT_MANAGER_KEY`) and a
   `website.host-name` like `minehut`, then `/testplayer reload`.
2. Start the connector on your PC (Bots page, "Run the AI on your PC", then "Get my connector token").
3. `/testplayer ai <target> on <your website name>`: the model runs on your PC's GPU.

Keep `ai.max-concurrent` low (2) and stay within your Minehut plan's RAM: each test player holds
`chunk-radius` chunks (49 at radius 3, shared when they're close together).

## A village for a video

```
/testplayer zone add village 60
/testplayer spawn Villager 5 wander
/testplayer personality Villager* random
/testplayer goal Villager* Build a little village together with your friends
/testplayer ai Villager* on DrLeebear
```

Then watch from spectator mode; their thoughts and chat show live on the Bots page. Small models say silly
things now and then, which tends to be part of the fun. Everything they say in chat starts with `[Test]`.

## The Bots page

With `website.enabled: true`, `website.url` and `website.manager-key` set to the website's `BOT_MANAGER_KEY`,
the server appears under **Bot hosts** as `website.host-name` (tagged "test players", with TPS, MSPT and chunk
counts), and its test players appear with the bots. From there staff can:

- **New bot** with this server as the host: creates a test player at `website.spawn-at` (or the world spawn),
  with an AI model and goal if picked. No Minecraft account needed.
- **Start / Stop bot**, and the bot's settings (AI model, goal).
- Set its **personality** in the New bot form or its settings.
- Talk to it in its chat box: `status`, `stop`, `auto` (AI on), `come x z`, `come` (to you, if your Minecraft
  account is linked and online), `follow <player>`, `wander [radius]`, `walk <direction>`, `say <text>`,
  `build <structure> [material]`, `undo`, `personality <text>`. A test player with an AI model answers
  anything else itself.

## Limits

- Routes are searched up to about 40 blocks at a time (further targets in stages). Somewhere with no way out
  on foot can still trap them (`bring` or `move` fixes that; long trips bring them home after 5 minutes).
- They keep chunks loaded and ticking (`chunk-radius`), but mobs don't spawn around them the way they do
  around real players, and they don't send network traffic. For network or login load, use real clients.
- They don't fight, and build only the structures above (no free-form building). They mine only in their
  town's mine and the nether, and break natural blocks (trees, leaves, dirt, stone) only when gathering or
  when one is in their way.
- Town buildings in progress are picked up after a restart; a `/testplayer build` order starts over.
  Inventories, finished builds and `undo` are kept.

## Building

```bash
mvn package
```

The jar is written to `target/`. Copy it over `ModdedrealmsTestPlayers-1.1.0.jar` here when releasing.
