# Moddedrealms Minigames

A Paper 1.21.11 plugin for the network's minigames server: a hub with **TNT Run** and **Murder Mystery**. When
there aren't enough real players for a match, **bots fill the empty spots** and play like people do.

Players get there with `/minigames` from any server (the `velocity-network` plugin on the proxy) or the NPC at
the SMP spawn, and go back with the red bed in the hotbar.

## The games

**TNT Run** (2 to 10 players): four floors of sand on TNT, one above the other. Every block vanishes a moment after
someone steps on it, so you have to keep moving; fall through all four floors and you're out. Everyone has two
double jumps (press jump twice in the air). Last one standing wins.

**Murder Mystery** (4 to 10 players), in a mansion of eight rooms (library, ballroom, kitchen, dining room, great
hall, bedroom, garden, study): one murderer, one detective, everyone else innocent.

- The murderer gets a knife 10 seconds in: hit someone to kill them, or right-click to throw it (6 s cooldown).
- The detective has a bow; the arrow comes back 5 seconds after each shot. If the detective dies the bow drops
  (glowing) and any innocent can pick it up.
- Gold spawns around the mansion: 10 gold buys a bow and an arrow.
- Shoot an innocent and you die too.
- Murderer wins by killing everyone; the others win by shooting the murderer or surviving 4.5 minutes.
- Name tags are hidden, dead players spectate and only talk to the dead, and bodies stay where they fell.

## Bots

A real player who joins waits 12 seconds for other real players; then bots join one by one, like people trickling
in, until the lobby has a believable number of players (a few short of full up to full). A real player who joins
a lobby full of bots takes a bot's spot. Bots never play without a real player: when the last one leaves, the
match ends.

Bots are mannequins (the player-shaped entity from 1.21.9) with made-up player names (Ash427, xMossx, pebble_digs)
and Minecraft's built-in skins (Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny, Zuri; slim or wide), so no real
account's name or skin is borrowed. They're **marked**: a grey `[BOT]` tag after their name in the scoreboard,
in join/leave messages, in results and before their chat lines. They don't take a player slot and aren't in the
tab list.

They move like players (walk 4.3 and sprint 5.6 blocks a second, jump 1.25 blocks, keep momentum in the air,
turn head and body separately) and each has its own skill, so some are good and some are clumsy:

- **In the lobby** they wander a few steps, look at people, jump now and then, and sometimes say hi or gl.
- **TNT Run:** they sprint toward the biggest patch of floor ahead, curve rather than zig-zag, jump gaps, some
  bunny-hop, and when they drop through a hole they sometimes save themselves with a double jump. Weaker bots
  look less far ahead, react later and now and then head the wrong way.
- **Murder Mystery:** they only know what they could have seen or heard. Innocents wander the rooms, collect
  gold, stick together, back off from someone running straight at them and flee from a murderer they've
  spotted. A bot that sees the knife or a kill may shout it in chat ("its ash!!"), and others take that as a
  hint. Bow holders hunt a murderer they know about, lead their shots and can miss; careful ones wait for a
  clear shot so they don't hit an innocent. A murderer bot blends in, picks someone alone, closes in with the
  knife hidden until the last moment, strikes, walks off and lies low before the next one; it gets bolder as
  time runs out and sometimes throws the knife at someone running away.
- After a game they say gg, ggs, close one, rematch?

## Commands

| Command | What it does |
|---|---|
| `/mg` | The game menu (also the compass in the hub) |
| `/mg tntrun`, `/mg murder` | Join a game |
| `/mg again` | Join the last game again (also the paper when you're out) |
| `/hub` (or `/mg leave`) | Back to the hub |
| `/mg stats [player]`, `/mg top [game]` | Wins and points; the hub shows the top 10 |

Staff (`minigames.admin`, op by default): `/mg list` (matches running), `/mg start [game]` (start the waiting match
now), `/mg fill [game]` (bots join now), `/mg end [game]`, `/mg debug` (what each Murder Mystery bot is doing),
`/mg bots on|off`, `/mg rebuild` (hub and free arenas), `/mg reload`.

## Stats

Only real players have stats (`plugins/ModdedrealmsMinigames/stats.yml`). A win in a match with at least one other
real player is worth 10 points, a win against only bots 4, and each Murder Mystery kill 1.

## Setup

1. Paper 1.21.11 behind Velocity with modern forwarding (`config/paper-global.yml` `proxies.velocity`, the same
   secret as the proxy). `allow-flight=true` in server.properties (double jumps).
2. Put `ModdedrealmsMinigames-1.0.0.jar` in `plugins`. On first start it builds a void world `minigames` with the hub
   at 0 65 0, three TNT Run arenas from x 1000 and three Murder Mystery mansions from z 1000 (about 9 seconds).
3. On Velocity: add the server (`minigames = "127.0.0.1:<port>"` under `[servers]`) and put
   `velocity-network/ModdedrealmsNetwork-1.0.0.jar` in the proxy's plugins for `/minigames`.
4. Optional: an NPC on another server that sends players here. With FancyNpcs (from the console):
   `npc create Minigames --location <x> <y> <z> --world <world>`, then
   `npc action Minigames any_click add send_to_server minigames`.

Config (`config.yml`): the server and label the red bed goes back to, how long a real player waits before bots
join, bot skill range and chattiness, the tag text, and per game the player counts, arenas, timings, gold and the
chance real players get the murderer or detective role (`human-role-weight`, 2 = twice as likely as a bot).

## Building

```
mvn package
```

Needs JDK 21+ and the Paper API from repo.papermc.io. The jar is `target/ModdedrealmsMinigames-1.0.0.jar`.
