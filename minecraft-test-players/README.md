# Moddedrealms Test Players

A Paper 1.21.11 plugin for performance testing: admins spawn **simulated test players** that walk around the
server, keep the chunks around them loaded and ticking like real players, and can be driven by an AI model.
They show up on the website's **Bots** page, where staff can create, stop and talk to them like the AI bots.

Test players are mannequins (the player-shaped entity added in Minecraft 1.21.9) that the server spawns
itself. Nothing logs in: they have no Minecraft account, don't take a player slot and exist only on this server.

## Install

1. Put `ModdedrealmsTestPlayers-1.0.0.jar` in the server's `plugins` folder. It needs **Paper** (or a Paper
   fork) 1.21.11. Spigot doesn't have the mannequin API.
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
| `move <target> <x y z \| here \| player>` | Walk somewhere (straight line, going around obstacles) |
| `follow <target> <player>` | Follow a player |
| `wander <target> [radius]` | Walk around randomly near where they are |
| `walk <target> [north\|south\|...\|yaw]` | Walk straight on, generating new chunks (turns when blocked) |
| `stop <target>` | Stand still, AI paused |
| `sprint <target> <on\|off>` | |
| `bring <target>` / `tp <name>` | Teleport them to you / you to it |
| `say <name> <text>` | Chat as the test player (always shown with the `[Test]` prefix) |
| `ai <target> <model\|on\|off> [staff]` | Let an AI model control them (see below) |
| `goal <target> <text\|clear>` | A goal their AI works toward |
| `list`, `info <name>`, `stats` | What they're doing; TPS, MSPT, chunks, entities, memory |
| `reload` | Re-read config.yml |

A typical load test: `spawn Load 100 wander`, `spawn Far 20 walk`, then watch `/testplayer stats` (or spark).

## AI models

`/testplayer ai Load1 qwen2.5:3b` makes Load1 ask that model what to do every 20 seconds: wander, walk to a
spot, follow a player, explore or stand still. Players who mention its name in chat get an answer. The model
runs on the Ollama at `ai.ollama-url` (this machine by default). Add a staff member's website name
(`/testplayer ai Load1 qwen2.5:7b DrLeebear`) to run it on their own computer through the website instead,
like the bots' "Staff member's computer" option: their connector must be running and the website link on.

`ai.max-concurrent` (default 2) caps how many model requests run at once, so the AI doesn't slow down the
test itself. Small models (`qwen2.5:1.5b`, `qwen2.5:3b`) are plenty for this.

## The Bots page

With `website.enabled: true`, `website.url` and `website.manager-key` set to the website's `BOT_MANAGER_KEY`,
the server appears under **Bot hosts** as `website.host-name` (tagged "test players", with TPS, MSPT and chunk
counts), and its test players appear with the bots. From there staff can:

- **New bot** with this server as the host: creates a test player at `website.spawn-at` (or the world spawn),
  with an AI model and goal if picked. No Minecraft account needed.
- **Start / Stop bot**, and the bot's settings (AI model, goal).
- Talk to it in its chat box: `status`, `stop`, `auto` (AI on), `come x z`, `come` (to you, if your Minecraft
  account is linked and online), `follow <player>`, `wander [radius]`, `walk <direction>`, `say <text>`.
  A test player with an AI model answers anything else itself.

## Limits

- No pathfinding: test players walk straight at their target and step sideways around walls and cliffs. A
  deep enclosed hole can still trap them (`bring` or `move` fixes that).
- They keep chunks loaded and ticking (`chunk-radius`), but mobs don't spawn around them the way they do
  around real players, and they don't send network traffic. For network or login load, use real clients.
- They can't break or place blocks, fight or use items.

## Building

```bash
mvn package
```

The jar is written to `target/`. Copy it over `ModdedrealmsTestPlayers-1.0.0.jar` here when releasing.
