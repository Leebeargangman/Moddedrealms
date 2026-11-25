# Bug report: Phase 1 audit (25 Nov 2026)

Covers **ColonySMP 2.5.0** (`colony-smp/`, ~42,000 lines) and **ModdedrealmsSMP 1.12.1** (`minecraft-smp/`, ~28,000
lines). Fixed in ColonySMP **2.6.0** and ModdedrealmsSMP **1.13.0** on branch `claude/colony-overhaul`.

## How the audit was done

The two plugins are about 70,000 lines, so each area on the brief's list was checked across all files (searching
for every place the risky pattern can happen), and the code at each hit was read in full. Areas:

| Area | What was checked | Result |
|---|---|---|
| NPC duplication | Every place NPCs are spawned (colony citizens, SMP spawn NPCs, town walkers) and what happens on restart and chunk load | Bodies are tracked by ID and never saved with the world, and leftovers are removed when a chunk loads, in both plugins. The duplicates players saw were **names**, not bodies (H1, M1). |
| Saving | ColonySMP SQLite snapshot, autosave, shutdown; every one of the SMP's 40 YAML saves | ColonySMP is safe (one database transaction per save, WAL, save on shutdown, backup at start). The SMP's YAML saves weren't crash-safe (H3). |
| Off-thread Bukkit calls | Every async task, future, executor and async event | Clean: chat handlers use thread-safe maps and the database writer gets a finished snapshot. |
| Memory leaks | Long-lived collections holding players or entities; repeating tasks | Clean: maps are keyed by UUID or name, and per-player tasks end themselves. |
| Unhandled exceptions | Repeating tasks | ColonySMP wraps every system's tick, so one failure doesn't stop the rest; Bukkit already does the same for the SMP's tasks. |
| Command permissions | `/colony`, `/colony admin`, `/civ`, `/mrsmp`, `/mrtown`, NPC dialogue choices | Clean: every staff subcommand checks permission, and dialogue choices work only once offered. |
| Item duplication | Chest menus (clicks, drags, shift and number keys), colony storage, armoury, barter, market freight | Clean: menu clicks are all cancelled, storage moves go through the ledger, and barter goes through the crash-safe journal. |
| Free money from colonies | Founding grant, treasury withdrawals, market freight, resettling | **Critical hole found** (C1). |
| Claiming others' land | Protection listeners (about 20 events) | Thorough; one gap (M2). |

## Findings

| # | Severity | Where | Problem | Fix | Status |
|---|---|---|---|---|---|
| C1 | **Critical** | `colony/ColonyManager.java:200` (create), `economy/EconomyManager.java:183` (withdraw) | `/colony create` is free and puts a 150-worth founding grant into the treasury as **real metal and gems**. `/colony withdraw 150` hands it over and `/colony disband` frees the slot. Repeating it gave unlimited iron, gold, emeralds and diamonds, which **are money** on the SMP ("money is metal"). | The grant is given **once per player per season** (`founders.yml`, written atomically). A second colony starts with an empty treasury and the founder is told why. | Fixed, tested (bot: round 1 withdrew a diamond, 4 gold, an emerald...; round 2 got "You've already had a founding grant this season"). |
| H1 | **High** | SMP `town/Officers.java:72`, also `Bandits.java:76`, `Bureau.java:193`, `Townsfolk.java:100` | **"Guard Finch" twice.** Each guard picks one of 16 surnames at random, up to 24 guards can be out, and nothing checks for names in use. Bandits, inquisitors and townsfolk had the same problem. | `TownManager.freshName`: a name no walker out right now uses. When the list runs out, a first name is added ("Guard Ned Finch"). The default officer list doubles to 32. | Fixed, tested: 19 guards forced out with the live 16-name list gave 19 different names. |
| H2 | **High** | `sim/Population.java:79`, `config.yml citizens.require-tools` | **New colonies can't work.** Workers need a real hoe, axe or pickaxe (`require-tools: true`), but the first settlers bring only bread, seeds and saplings. The only warning is a chat line. It's a likely reason the one player colony (`best team ever`) stalled and the players left. | The first settlers bring a stone hoe, axe and pickaxe (`citizens.starting-tools`, a new key so it also applies to the old live config). | Fixed, tested: new colony's farmer carries the stone hoe, forester the stone axe, pickaxe in the armoury. |
| H3 | **High** | SMP: 40 `YamlConfiguration.save(file)` calls (town-data, bank, businesses, market, mint, stocks, quests, crates, plots...) | Bukkit writes YAML straight over the old file. A crash, a kill by AMP or a full disk mid-write leaves a **truncated or empty data file** (players' money, loans, businesses). | `SafeYaml.save`: write `name.tmp`, force it to disk, then rename over the old file in one step. All 40 call sites switched. | Fixed, tested (all data files load after restart). |
| M1 | Medium | `util/Names.java`, `sim/Population.java:51`, `prison/*` | Colony citizens get a random first + last name with **no check within the colony**, so two "Ines Rowe" could live in one village. | Every way into a colony goes through `ColonyManager.addCitizen`, which picks a free name (same surname first, so families stay families). Existing duplicates are renamed when the colony loads. | Fixed, unit-tested (3,000 names in one colony, no repeats). |
| M2 | Medium | `colony/ClaimListener.java` | No `BlockDispenseEvent` check: a dispenser **just outside a colony's border could pour lava or water, light fires or place blocks inside it**. | Dispensers outside a colony can't empty buckets, use flint and steel or fire charges, place blocks, bone meal, armour stands, minecarts or boats into it. Dispensers inside the same colony work as before. | Fixed, not yet tried in game. |
| M3 | Medium | `command/AdminCommand.java:64` (`/colony admin reload`) | Bukkit's `reloadConfig()` swallows YAML errors and returns an **empty config**, so one typo silently put every setting back to its default on a live server. | `reloadSafely()`: config.yml and messages.yml are parsed first, and a broken one is refused with the error ("nothing changed"). Same for `/mrsmp season reload`. | Fixed, tested (a broken messages.yml was refused; the good one reloaded). |
| L1 | Low | `colony/...` founding grant | A player can still withdraw their one grant (about 150 worth) into their own pocket without ever placing the Town Hall. | Accepted for now: it's once per season. Phase 2's starter camp places the Town Hall at once, which closes it. | Open (by design). |
| L2 | Low | `gui/StorageMenu.java:72` | The armoury slot index is fixed when the menu is drawn. With two people in the same colony's armoury at once, the second can take a different item than the one they clicked. No item is duplicated or lost. | Re-check the item under the cursor before taking it. | Open, Phase 3 menu rework. |
| L3 | Low | SMP `quest/QuestManager.java:101` | An async timer whose only job is to schedule a sync task. Harmless, just odd. | Make it a plain sync timer. | Open. |
| P1 | Perf note | ColonySMP sim | The first step of a colony after a restart catches up on every day it missed (24 ms for `best team ever`, 916 game days in). It's once per restart per colony, and later steps take under 1 ms. | Keep an eye on it with Spark once more colonies exist. | Note. |

**Not audited yet** (they belong to later phases and are behind flags): block damage in player wars and raids (Phase 5 has to
record and roll back every change), the empire and siege systems (new code), and cross-server Battles messaging.

## Tools added for staff

- `/colonyadmin` (alias `/cadmin`), with each subcommand needing `colonysmp.admin.<name>`:
  - `cleanup [confirm]`: lists, then removes, colony bodies nobody tracks, second bodies of one citizen, bodies of
    citizens who no longer exist, riderless mounts, SMP NPC duplicates and orphaned town walker bodies.
  - `debug <colony>`: owner, tier, treasury; Town Hall chunk load level, active or not; bodies against the cap; jobs;
    stores and armoury; buildings and orders; raid, war, grace and shield; simulation timings; the first 12 citizens.
  - `reload`: config.yml and messages.yml, refused if broken.
  - `raid <colony|all> [strength]`, `event <name> start|stop`, `give <player> <resource> <amount>`, `tier <colony> <n>`,
    `funnel` and `season end` (the last two say they're off until their phase).
  - Anything else runs the older `/colony admin` tools (`inspect`, `perf`, `db`, `bodies`...).
- `config.yml`: `features:` switches for every big system, plus `debug.logging` with a per-system filter.
- `messages.yml`: MiniMessage text for everything new; values like colony names are escaped.
- **Spark is not installed** on the SMP. With it: `/spark profiler start --thread "Server thread"`, play for 5 minutes,
  `/spark profiler stop`; ColonySMP's work shows under `com.colonysmp` (the 1-second loop does bodies, simulation, raids
  and the living world).
