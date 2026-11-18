# Moddedrealms AI bots

AI-driven Minecraft players that staff control from the website's **Bots** page (staff only). Each bot is a
**real Minecraft client** (Fabric + [Baritone](https://github.com/cabaletta/baritone)) running headless, steered by
an AI agent and a local LLM (Ollama). Bots can play alone or in **teams with a leader AI**.

What they do:

- **Talk to staff**: chat from the Bots page, or whisper in-game. Simple commands (`status`, `inv`, `come`,
  `come x z`, `follow`, `stop`, `auto`, `goto x z`, `give <item> [n]`, `build <file> [x y z]`, `rejoin`) work
  instantly; anything else is understood by the AI ("go chop some wood", "from now on focus on iron gear").
- **Ignore other chat**: the AI only reads staff, messages that name the bot, and players close by. With strangers
  it's careful: keeps its distance, may whisper something harmless, never gives out coordinates, and tracks trust
  per player. Anyone who attacks it becomes hostile for good.
- **Survive**: eat, wear the best armor, keep a totem in the off-hand, fight or flee mobs, run from hostile players,
  hide at night, sleep in beds, progress from wood to diamond tools, strip mine, cook, stash surplus.
- **Travel with Baritone**, and through the **nether** for long trips (over 1000 blocks by default): it uses a portal
  it knows or sees, or builds and lights one if it carries 10 obsidian and flint and steel, crosses at 1/8 scale and
  comes out near the target. Every portal it uses is remembered.
- **Build Litematica schematics**: upload a `.litematic` on the Bots page, check the materials list, and the bot builds
  it with Baritone (`#build`). In survival it needs the materials; on creative servers it supplies them itself.
  Litematica format 6 files (MC 1.18-1.20) are upgraded automatically.
- **Auto log-off**: at very low health while taking damage it leaves the server, then rejoins after 5 minutes or
  when staff press **Rejoin**.
- **Teams** (2-5 bots): they meet at the leader (through the nether if far), then the leader AI hands out jobs every
  90 s (wood, food, mining, guarding, building) from everyone's status. Followers stay with the team, follow the
  leader when it travels, defend each other from mobs, share food with a hurt teammate and never fight each other.
  Staff orders always win. They coordinate through the bot manager, not in-game chat; turn on **Team chat** for a
  team to have them whisper each other about jobs, food and trouble (never coordinates).
- **Anarchy mode** (per bot, in its settings), for servers like 2b2t and 9b9t: on joining, the bot heads straight for
  the nearest nether portal, travels far through the nether in one direction until it gets hungry (or
  `behavior.anarchy.maxNetherBlocks`, 15000 by default), then takes the next portal it finds back to the overworld
  and survives normally out there. It only uses portals other players left (anarchy worlds are full of them). Teammates take the same direction. It starts over after dying without a bed.

## How it fits together

```
Staff browser ──> website (server/bot-hub.js) <── bot manager on the VPS        (outbound long-polling only)
                          ^                   <── bot manager on a gaming PC
                          └── connector.js on a staff member's PC + their Ollama

bot manager (ai-bot/manager) runs several bots:
  agent (Node: brain, reflexes, skills, team) ──TCP 127.0.0.1──> bridge mod inside a real client
  real client = Minecraft 26.1.2 + Fabric + Baritone + ViaFabricPlus + bridge mod, under Xvfb
```

- **ViaFabricPlus** lets the 26.1.2 client join servers on other versions: before joining, the manager pings the
  server and picks its version (servers that accept 26.1.2, like 9b9t and 2b2t, need no translation).
- Nothing listens on the internet: managers and connectors connect out to the website.
- A real client moves like a normal player, so anti-cheats (tested with Grim) don't pull it back.

```
manager/            Node bot manager + AI agent
  src/manager.js    entry point: bots, teams, website link
  src/agent/        brain (LLM + rules), reflexes, skills, travel (nether), social rules, combat
  src/team/         team leader logic
  src/crowd/        crowds of lightweight bots (crowd.js) and the process that runs them (worker.js)
  src/bot/          one bot's lifecycle; Microsoft / offline accounts
  src/client/       launcher (downloads Java, Minecraft, Fabric, mods) and the bridge connection
  src/build/        Litematica reader / writer
  deploy/           install-deps.sh, start-container.sh
bridge-mod/         Fabric mod (Java 25) the manager talks to; dist/ has a ready-made jar
connector/          connector.js for staff who run the AI on their own PC
```

## Running a bot host (VPS or PC)

Needs Linux (or WSL), Node 22+, Xvfb, about **2 GB of RAM per bot**, and Ollama for the AI.

```bash
sudo bash ai-bot/manager/deploy/install-deps.sh
cd ai-bot/manager && npm ci
cp manager.example.json manager.json
```

Edit `manager.json`:

```json
{
  "name": "pc",
  "website": { "url": "https://your-website", "key": "<BOT_MANAGER_KEY from the website's .env>" },
  "maxBots": 5,
  "defaults": { "server": "152.53.114.44:25576", "ai": { "model": "qwen2.5:7b" } }
}
```

A bot runs on the host it was created on. To run bots on the VPS, the VPS needs its own manager (these same steps,
with `"name": "vps"`); until then everything runs on whichever PC has one. Only one manager runs per folder.

Start it with `node src/manager.js`. On a desktop PC you can have it start whenever you log in (and restart if
it crashes) with the user service in `deploy/moddedrealms-bots.service`; the file explains the two commands. Without a website configured it runs the bots and teams marked `autoStart` in
`data/bots.json` / `data/teams.json`, and writes what each bot is doing to `data/status.json` every 5 seconds. In a container without systemd, use `bash deploy/start-container.sh`, which
restarts the manager if it crashes. The first bot start downloads Java 25, Minecraft and the mods (about 400 MB).

On the website, set `BOT_MANAGER_KEY` in `.env` (any long random string, e.g. `openssl rand -hex 24`), and the same
value in each manager's `manager.json`. Then open **Bots** in the staff menu.

### Models

The Bots page lists models (Qwen 2.5 from 0.5b to 32b, Llama 3.x, Gemma 2, Mistral, Phi 3.5, or any Ollama name) and
marks each one ✓ or ✗ for the computer it would run on, from that machine's free RAM and GPU. A model the bot host
doesn't have yet downloads when the bot starts; the bot plays on its built-in rules until it's ready. The manager
refuses to start a bot if it would leave less than `reserveMemMb` (default 1536 MB) free for the system.

Pull one model per machine and use it for every bot there: `ollama pull qwen2.5:7b`. On an 8 GB GPU,
**qwen2.5:7b** fits entirely in VRAM and answers in a few seconds, and Ollama serves several bots in parallel.
Mixing models (e.g. a 14b leader plus 3b followers) on one 8 GB GPU makes Ollama stall while it swaps them.
CPU-only hosts: use `qwen2.5:3b`, or point the bots at a staff member's PC with the connector.

### Accounts

Add accounts on the Bots page:
- **Offline names** work on offline-mode servers, such as the test server.
- **Microsoft accounts:** press "Link a Microsoft account" and enter the code at microsoft.com/link. The account
  needs **two-step verification** turned on, or Microsoft refuses the sign-in. Tokens stay on the bot host.

### Running the AI on your own PC

Bots page → "Get my connector token" → download `connector.js` → `node connector.js --site https://your-website --token <token>`.
Then set a bot's "AI runs on" to "My computer". Bots then use your Ollama, even though they run on the VPS.

## Personalities

Every AI bot can have a personality: a preset (`builder`, `explorer`, `leader`, `joker`, `nervous`, `grumpy`,
`friendly`), `random`, or your own words. Set it in the New bot form or the bot's settings on the Bots page.

- It goes into the bot's AI prompts, so it shapes what the bot chooses and how it talks.
- **Likes and dislikes** are activities: `build`, `gather`, `mine`, `explore`, `fight`, `follow`, `stay`, `chat`.
  Presets come with their own; type your own to override them.
- **Personality on/off** switches it off without losing it.
- **Free will**: a bot may say no to an order it dislikes ("n-no way, that sounds dangerous"). Asking again within
  3 minutes makes it go along, grumbling. `stop` is always obeyed.

## Crowds

A crowd is many lightweight bots on an **offline-mode** server you run: mineflayer clients without a game window,
about 30 MB each, so a PC can run dozens next to the AI bots. No server plugin is needed.

```
team leader (AI bot) ──orders──> team members (AI bots: real clients, mine, build, survive)
        └──"crowd" action──> crowd (lightweight bots: follow, wander, gather, chat)
```

- Create one on the Bots page (Crowds, New crowd): server, how many, a name prefix (`Villager` makes Villager1,
  Villager2...), personalities (a random one each, all the same, or none), free will, and a leader.
- The **leader** is one of the AI bots on the same server. The crowd follows it, and its AI can tell the crowd to
  `follow`, `wander`, `gather` (come to where the leader is) or `stop`. Staff can do the same from the page.
- Personalities change how each one behaves: explorers roam and drift off, nervous ones stay close, and with free
  will some refuse orders they dislike (an explorer told to stand still keeps wandering for a couple of minutes).
  Each member's personality and free will can be switched on and off on the page.
- **Chat**: with an AI model they answer players who say their name and now and then say something in character
  (`chatEverySeconds`, default 60). Without one they use short built-in lines.
- **Settle**: `settle` (a button on the page, or the leader's `crowd` action) founds a settlement where the
  leader stands. Each settler gets a plot on rings around the camp, punches trees for logs (by hand, like a new
  player), crafts planks in its inventory and builds itself a 5x5 hut with a doorway facing the camp. Once its
  hut is up, its personality decides its job: builders look after the settlement, scouts explore and report
  back, keepers (nervous, grumpy) stay by the camp, entertainers (jokers) keep everyone company. At night they go
  into their huts. Settlers only break logs (and leaves), only place planks on their own plot, keep out of spawn
  protection, and build on open ground (they won't build in a forest, though they fetch wood from one).
- Outside settling they don't mine, build or fight. They may break leaves to get out of a dense forest.
- `maxCrowdBots` in manager.json (default 50) caps how many run on one host; they join 1.5 s apart.

## Building the bridge mod

`bridge-mod/dist/ai-bridge-1.0.0.jar` is ready to use. To change the mod you need JDK 25:

```bash
cd ai-bot/bridge-mod && JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The launcher prefers `build/libs/` over `dist/`.

## Limits

- **Crowds only join offline-mode servers.** On an online-mode server each bot needs its own Microsoft account, like
  the AI bots. Only use crowds on servers you run.
- **Crowd pathfinding** struggles in very dense forests (pale gardens, jungles); bots there may stay put until
  ordered elsewhere.
- **Anti-cheats** (Vulcan, Grim) set crowd bots back every step: mineflayer's movement isn't close enough to a
  real client's. Exempt the crowd's names in the anti-cheat, or use crowds on a server without one. (The AI bots
  are real clients and aren't affected.)
- **Settling is slow**: everything is done by hand with real blocks, so a hut takes a settler roughly 10-20
  minutes depending on how many trees are within reach. Savanna and plains with scattered trees work best.

- **Baritone** can't place some blocks from a schematic, such as wall torches. Builds stop after a minute without
  progress and report what's left.
- **Bots don't use elytra or do crystal PvP.** Against skilled players with hacked clients, running is their defence.
- **Spawn protection:** servers stop non-ops from digging or building near world spawn (16 blocks on Paper). Bots
  walk out of that area before working; set `behavior.spawnProtection` if the server uses another radius (0 = none).
- **Anti-cheats:** bots turn towards what they hit or right-click over a few ticks, like a mouse, and vary their
  click timing. Tested with Grim and Vulcan.
- **Microsoft tokens last about a day.** A bot that has run longer restarts its client to sign in fresh when it
  rejoins.
