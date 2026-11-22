# ModdedrealmsGuard

Anticheat for the Moddedrealms Paper 1.21.11 servers, built around one rule: **a legit player must never be banned.**
Comes with Hypixel-style replays so staff can watch exactly what a flagged or reported player did.

Runs on Bedwars201 ("Bedwars2", the colony/boss SMP) next to Vulcan, in **shadow mode** to start with.

## How it avoids false bans

- Only **certain** flags can ban: things a vanilla client physically cannot do, measured with wide margins
  (reach is the shortest distance across the target's last 0.7s of positions and three eye positions; fly allows
  2.3 blocks of rise plus jump boost; speed needs 2.2x the vanilla maximum for three windows in a row).
- A ban needs the flag to **repeat** (e.g. 6 certain reach flags within 60s).
- Nothing counts towards a ban while TPS < 18, ping > 300ms, for Bedrock (Geyser) players, or for clients older than
  1.21.2. Those still alert staff.
- Anything that throws players around switches movement checks off until they land: knockback, explosions,
  wind charges, slime/bed bounces, pistons, elytra, riptide, spears, vehicles, levitation/slow falling, ice,
  liquids, climbing, teleports.
- Pattern checks (KillAura angle, AutoClicker, Timer, X-Ray) are **alert only** by default.
- No permanent bans: the ladder is `6h → 1d → 3d`, hard-capped at 3 days (`bans.max`).

## Checks

| Check | What | Bans by default |
|---|---|---|
| Fly | rising without support, hovering | yes (6 / 60s) |
| Speed | sustained horizontal speed far over vanilla | yes (4 / 60s) |
| Reach | hits beyond interaction range + 1.1 | yes (6 / 60s) |
| Multi-Aura | two different entities hit in one client tick (and within 50ms) | yes (5 / 60s) |
| Bad Packets | pitch beyond ±90°, attacking yourself | yes (3 / 60s) |
| Timer | more game ticks than real time allows | alert |
| KillAura | hitting far outside your view | alert |
| AutoClicker | sustained > 22 CPS | alert |
| X-Ray | many rare ore veins with little stone mined | alert |

## Replays

Every online player (and any mob fighting one) is kept in a 2-minute rolling buffer. A flag, a ban, `/report` or
`/replay save` writes a clip of 60s before and 20s after, with every player and mob within 48 blocks, block
changes and chat. Staff watch it in spectator mode: players appear as Mannequins wearing their skin, visible only
to the viewer. Controls: pause, ±10s, 0.25x–4x, POV (see through the subject's eyes).

Clips live in `plugins/ModdedrealmsGuard/replays/` and are deleted after 14 days.

## Commands

| Command | Permission | |
|---|---|---|
| `/report <player> <reason>` | `mrguard.report` (everyone) | saves a replay and alerts staff |
| `/replay list [player]`, `/replay play <id>` | `mrguard.staff` | list / watch |
| `/replay save <player> [seconds]` | `mrguard.staff` | save the last N seconds now |
| `/replay pause|speed <x>|skip <s>|pov|stop` | `mrguard.staff` | playback controls (also clickable in chat) |
| `/mrguard status|alerts|history <player>` | `mrguard.staff` | |
| `/mrguard mode <shadow|enforce>` | `mrguard.admin` | switch from shadow to real bans |
| `/mrguard unban <player> [forgive]` | `mrguard.admin` | `forgive` also takes one offence off the ladder |
| `/mrguard exempt <player> <minutes>` | `mrguard.admin` | |
| `/mrguard debug <player>` | `mrguard.admin` | logs every movement decision to the console |

`mrguard.bypass` skips all checks and is not given to ops by default.

## Going from shadow to enforce

1. Leave it in shadow mode for a few days next to Vulcan. Every flag is in `plugins/ModdedrealmsGuard/flags.log`;
   "WOULD BAN" lines are what enforce mode would have done.
2. Watch the replays of anything marked certain. If a legit player shows up, raise that check's margin or turn its
   `ban` off in `config.yml`, then `/mrguard reload`.
3. `/mrguard mode enforce`, then move Vulcan's jar out of `plugins/` at the next restart.

## Build

```bash
~/.local/share/apache-maven-3.9.9/bin/mvn -f minecraft-guard/pom.xml package
```

Needs PacketEvents (already on the server) for the packet checks; without it the others still run.
