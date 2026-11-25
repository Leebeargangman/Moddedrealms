# ColonySMP Overhaul + Season 1 "The Frozen Crown": plan

Status: **waiting for approval**. Written 25 Nov 2026. Nothing has been changed in either plugin yet.

## 1. What is there today

| | |
|---|---|
| **Server** | Bedwars201 is the "Kingdoms SMP": Velocity name `SMP`, the first server players land on. It runs Paper **1.21.11**; 26.2 clients join through ViaVersion on the proxy. Everything here keeps building against the 1.21.11 API. |
| **ColonySMP 2.5.0** | `colony-smp/`, about 42,000 lines in 203 files, 18 unit-test classes. It has 35 jobs, 38 building types, Town Hall levels 1–5, milestones, research, raids, requests, Christmas trees, war, diplomacy, prisoners, squads, and a living world of NPC civilisations. |
| **ModdedrealmsSMP 1.12.1** | `minecraft-smp/`, about 28,000 lines. It runs the spawn plaza and its NPCs (Elder Rowan, Herald Kael, Enchanter Vael, Captain Hale), the town, the Town Watch guards, quests, crates, coins, `/start`, the starter kit and Insights. It compiles against ColonySMP. |
| **NPC library** | None. ColonySMP citizens are database records with a UUID; while the colony is loaded they get a Villager body that isn't saved with the world. SMP NPCs are Mannequins that are respawned from `npcs.yml`. Both remove stray bodies when a chunk loads. |
| **Storage** | ColonySMP: SQLite `colonies.db` (snapshot of everything, one database transaction, written on a background thread every 30 s when changed, every 5 min, and on shutdown; backup at start) plus `journal.db` for trades. ModdedrealmsSMP: about 35 YAML files. |
| **Dependencies** | ColonySMP: none (Paper only). Installed on the SMP: WorldGuard, WorldEdit, PlaceholderAPI, TAB, Vault, Essentials, LuckPerms (shared MariaDB), FancyNpcs, Shopkeepers, MythicDepths, Vulcan, ModdedrealmsGuard. **Not installed:** Spark, ModelEngine/BetterModel, CoreProtect, BlueMap/Dynmap, Citizens, Floodgate (Bedrock players come in through Minehut's Geyser). |
| **Battles** | TrenchWar on Velocity server `arena` (also on `bedwars`). It's a third-party jar with no source, so its combat can't be reused. ColonySMP's own soldiers and squads are used instead. Velocity has the BungeeCord plugin-message channel on, so "Join the Battles" can send players across. |
| **The numbers** | 13 new players on 25 Nov: 7 left in under a minute, 2 stayed 5–30 min. Earlier logs: median first visit 29 s. The one player colony lost all its settlers within two in-game days. |

### Already known
- **"Guard Finch"**: Town Watch guards (`minecraft-smp/.../town/Officers.java:72`) pick a random surname from 16, with up to 24 guards out and no check for names in use. Two "Guard Finch" at once is close to certain. ColonySMP citizens have the same problem on a smaller scale (`util/Names.java`: first + last name, no check within the colony).
- **A new colony may not be able to work**: `citizens.require-tools: true`, but the founding provisions are bread, seeds and saplings, with no tools. This is a likely part of why the kids' colony died. To be confirmed in the bug hunt.

## 2. Decisions taken (25 Nov)

1. **Elder Rowan** is the glowing "START HERE" guide. Herald Kael keeps the bosses.
2. **Reshape, don't rewrite.** The simulation and all data stay. The 6 tiers, 8 headline jobs, a new menu and the onboarding go on top. The rest is hidden behind "More…" or unlocked later. Existing colonies keep working.
3. **Deploys:** Claude tests on a local test server, shows you, and stages a jar on Bedwars201 only after your OK (backup first, live at the next restart).
4. **Daily SMP task paused** until launch. The 20-minute busy-watch stays on for player reports and serious bugs.
5. **Done already:** `/pl`, `/plugins` and `/version` are denied to the `default` group on the shared LuckPerms (SMP, Lobby, Arena, Bedwars). Staff groups have them allowed explicitly. Town01 has its own LuckPerms and was not changed.
6. **Most features release early**: empires, armies and siege weapons come out by 13 Dec, before the launch (see section 3).
7. **Empires and siege weapons are on hold** (owner, 25 Nov, later the same evening): they wait until they're perfected, with no
   date. Phases 4 and 5 and the siege models (which were to start 26 Nov) don't start for now and stay behind their flags.
8. **Release 1 went live early, without the Christmas features** (owner, 25 Nov): the Phase 1 fixes and admin tools went
   live on 25 Nov at 23:24 CET. The advent calendar, countdown sign and `/titles` are in the jar but switched off by
   `season.enabled: false` in `season.yml` until the owner wants them (the doors open from 1 Dec, so a door that passes
   while the season is off is missed).

## 3. Timeline

Today is Wed 25 Nov. Each **deploy** is one planned restart, early morning UK time, after your OK.

**Changed 25 Nov: most features release early.** Empires, armies and siege weapons come out in mid December, before the launch, not in January. The siege weapon models start on 26 Nov alongside the code, because they're the slowest part and don't depend on it.

| When | Work | Deploy |
|---|---|---|
| 25–30 Nov | **Phase 1** bug hunt, fixes and admin tools · **Advent calendar + countdown** · siege models begin (Blockbench files, textures, pack) | **Mon 30 Nov** (advent opens 1 Dec) |
| 1–6 Dec | **Phase 2** the first 5 minutes + funnel · **Phase 3** colony menu, 8 jobs, winter needs, **all 6 tiers**, guards, help/Codex | Sun 6 Dec |
| 7–13 Dec | **On hold (decision 7):** **Phase 4** empires (vassals, outposts, empire menu, borders, titles, diplomacy, leaderboards) · **Phase 5** armies (squads, banner orders, marching) and **siege weapons** (ballista, catapult, cannon with models, block damage with rollback) | **Sun 13 Dec** |
| 14–17 Dec | Season events up to Christmas Day, frost bandits, balance across everything, `TESTING.md`, `PLAYER_GUIDE.md`. **Code freeze Wed 16 Dec** | Thu 17 Dec |
| **Fri 18 Dec** | **Season 1 launch** (flags flipped, no new code) | — |
| 19–31 Dec | Frost raids (26–31 Dec), Krampus boss, NYE fireworks + "The Betrayal" | ~24 Dec, ~30 Dec |
| January | The assault on Hale's ice fortress, The Crowning, hall of fame, season end | as ready |

Late November was too soon for empires and siege weapons. Phase 1 and the advent calendar fill the days until the 30 Nov deploy, and empires and siege weapons need tiers 4–6 (Phase 3) first.

The safety net stays: anything not solid by the 16 Dec freeze stays off behind its flag at launch and goes live in a later deploy. The order of the priority list from the brief still decides what gives way if time runs short: bug fixes, the first 5 minutes, colony core and the advent calendar come first.

## 4. Phase 1: bug hunt and stability (25–30 Nov)

### 4.1 Audit → `BUG_REPORT.md`
Every class of both plugins gets read for the list in the brief: duplicate or orphaned NPCs, data not saved on shutdown or crash, Bukkit calls off the main thread, maps that hold Player/Entity objects, tasks never cancelled, unhandled exceptions, commands without permission checks, and exploits (item dupes, free money from colonies, claiming others' land). Each finding gets a severity, file:line and the fix. The audit also covers why new colonies die (food, tools, raids, disease).

### 4.2 Fixes
- **Unique names**: a name registry per colony (ColonySMP) and per town (Town Watch and townsfolk). A name already in use is never handed out, and the guard surname list grows to at least the guard cap. Names stay stable for a citizen's whole life.
- **NPC identity**: ColonySMP bodies already carry the citizen UUID. Before spawning, check that no live body with that UUID exists anywhere, not only in the map. Same check for SMP Mannequins by NPC id.
- **`/colonyadmin cleanup`**: finds bodies with no record, two bodies for one record, SMP NPCs spawned twice, and leftover mounts and seats. Lists them, then removes them with `confirm`.
- **Saving**: ColonySMP's SQLite snapshot is already atomic. Autosave minutes stay in config and a save on shutdown is checked. ModdedrealmsSMP's YAML files get **write to `.tmp`, then rename**, so a crash can't leave a half-written file.
- All critical and high findings fixed. Medium and low findings fixed where cheap, otherwise listed.

### 4.3 Admin and debug tools
- New command **`/colonyadmin`**, permissions `colonysmp.admin.*`. `/colony admin …` keeps working.
- `debug <colony>`: citizens and bodies, jobs, stores, build orders, tasks, chunk load level, sim timing.
- `reload`: config + `messages.yml`, refusing a broken file instead of loading half of it.
- `config.yml` → `debug.logging: false`, a per-system debug toggle.
- **Spark**: I recommend installing it on the test server, and on the live SMP if you agree. It's free and only reads. Heavy tasks I find get noted in `BUG_REPORT.md`.

### 4.4 Performance (rules for everything after)
ColonySMP already simulates in full only while the Town Hall chunk is entity-ticking and computes the rest in the background, and it already caps bodies (60 per colony, 300 total). Added: the active radius goes in config, plus caps for siege weapons and projectiles, and pathfinding work spread over ticks with a per-tick budget. The test is 20 colonies plus 1 raid on the local server with Spark, aiming for 20 TPS and under 10 ms/tick for ColonySMP.

## 5. Advent calendar and countdown (by 30 Nov)

This lives in **ModdedrealmsSMP**, which already has crates, coins and daily streaks.
- `/advent` (and a clickable item at spawn): a 6-row GUI with 24 doors. Door *n* opens only on *n* December, Europe/London time. Past doors show "missed" and can't be claimed. Each player claims each door once (stored per UUID, write-then-rename).
- Rewards in `season.yml`: items, coins, crate keys, cosmetics (titles/banners) and colony boosts that carry into Season 1. I'll fill in a default list that you edit.
- Countdown: a TextDisplay hologram at the plaza, placeholder `%mrsmp_season_countdown%` (PlaceholderAPI, for TAB/scoreboard), and a countdown on the website. Velocity/Minehut MOTD can't read in-game placeholders, so the MOTD gets the date written out.
- Flags: `features.advent`, `features.countdown`.

## 6. Phase 2: the first 5 minutes (1–6 Dec)

Built in both plugins: SMP for spawn, Rowan, bossbar and kit; ColonySMP for the camp, settlers and tasks.

| Time | What happens | Built where |
|---|---|---|
| 0:00 | First join: placed a few blocks from Elder Rowan, facing him. Title "Welcome to ModdedRealms" / "Build your kingdom. Rule an empire." | SMP `Welcome` |
| 0:00 | Rowan glows, has particles and a big "⚔ START HERE ⚔" hologram. Bossbar: "Talk to Elder Rowan". | SMP `PlazaBuilder`, new `Onboarding` |
| 0:00 | Other spawn NPCs say "Talk to Elder Rowan first" to new players until they have made their choice with Rowan. *(The brief says "until they've founded a colony". I suggest "until they've chosen" so players who pick Battles aren't locked out. Say if you want it the other way.)* | SMP `npc` |
| 0:30 | Rowan: a 3–4 line intro, then a GUI: **🏰 Found a Colony** (highlighted) / **⚔ Join the Battles** (sends to `arena`, configurable). | SMP |
| 1:30 | Found a Colony → teleport to one of 3 **pre-checked free sites** (a background scanner keeps a pool, so the click is instant), or pick one. A **starter camp** is placed: Town Hall core, campfire, tent, chest. **3 settlers** walk over and greet the player by name. | ColonySMP `Onboarding`, `StarterCamp` |
| 1:30 | Starter kit: tools, food, a **Colony Charter** book (right-click opens `/colony`). Settlers get their own tools. Title "Your colony, [Name], has been founded!" with sound and particles. | ColonySMP |
| 3:00 | Task 1: open the colony menu (arrows in the GUI point at the key buttons). Task 2: click a settler → Woodcutter (sites are chosen next to trees, so chopping starts at once). Task 3: place a **Starter Farm** item (stamps a small farm and registers the building). Each pays out straight away. | ColonySMP |
| 5:00 | Reward: coins + cosmetic banner. Bossbar: "Grow to 5 settlers". | both |

**Always on:** a task bar (bossbar, `/colony tasks off` to hide it), one-time action-bar hints, `/colony help` as a paged GUI (Basics, Jobs, Buildings, Tiers, Empire, War, Siege Weapons), the **Colony Codex** book with pages unlocked by progress, a nudge after 60 s idle from Rowan or the nearest settler, and a "while you were away" summary on login.

**Funnel** (ColonySMP owns it; SMP reports its steps through `ColonyApi`): `first_join`, `talked_to_rowan`, `chose_colony`, `chose_battles`, `colony_founded`, `opened_menu`, `first_job_assigned`, `first_building`, `reached_5_minutes`, `left_server`, `returned_next_day`. These go to a new `funnel` table (numbered migration, backup first). `/colonyadmin funnel [24h|7d|all]` shows a step-by-step count with % kept, and a placeholder `%colonysmp_funnel_step%`. The existing `/mrsmp insights` stays.

Flags: `features.onboarding`, `features.starter-camp`, `features.funnel`.

## 7. Phase 3: colony core (7–13 Dec)

- **`/colony` menu**: one chest GUI with overview (name, tier, population, happiness), resources, settlers and jobs, buildings, tasks/goals, military, diplomacy, upgrades and settings. Every button has a one-line lore. The existing deeper menus stay one click further in.
- **8 headline jobs**: Farmer, Woodcutter (today's Forester), Miner, Builder, Guard, Blacksmith, **Engineer** (new; crews siege weapons from 13 Dec), Soldier. The other jobs unlock by tier under "More jobs". Assign by clicking a settler or from the menu. Today only fighting, guarding and a few specialist jobs swing a tool. The 8 headline jobs get visible work: the tool in hand, swings while working, and the goods carried shown in the off-hand.
- **Winter needs** (new): settlers need food and **warmth**. Heat comes from campfires, hearths and houses within range, and firewood is used up. A shortage lowers happiness, then slows work, then settlers leave (one at a time, never all at once). No colony is destroyed. `season.needs` in config switches it per season. Flag `features.winter-needs`.
- **Tiers**: Camp, Village, Town, City, Kingdom, Empire map onto Town Hall levels 1–5, and Empire = level 5 + vassals. Requirements and costs are all in config. A tier-up is a button press once the requirements are met, with title, sound, firework and a server-wide line. All 6 tiers come in this phase, because empires and siege weapons need tiers 4–6. Flag `features.tiers`.
- **Buildings**: the existing blueprint and builder system stays. Starter blueprints (house, farm, storehouse, hearth, barracks, wall/gate, watchtower, workshop, market, castle keep) are loaded from `plugins/ColonySMP/blueprints/` so you can add your own.
- **Land**: claims grow with tier (config). The existing protection stays and its rules get shown in the menu. It already respects the SMP's no-colony zones (fountain, Meadowbrook) through `ColonyApi`, but it **ignores WorldGuard** today. Added: no founding or claiming over a WorldGuard region (soft-depend, checked only if WorldGuard is installed).
- **Guards** defend automatically (already there; checked against frost bandits).

## 8. Season 1 content (`season.yml`, mostly SMP)

| Date | Event | For launch? |
|---|---|---|
| 1–24 Dec | Advent calendar | **30 Nov** |
| to 18 Dec | Countdown | **30 Nov** |
| from 18 Dec | Snow theme, winter needs, frost bandits, festive cosmetics | yes |
| from 18 Dec | **Server goal**: deliver presents/supplies to the capital tree; everyone sees the progress bossbar; a reward for all at the target | yes |
| 24 Dec | **"Save Winterfest"**: bandits raid the capital and steal the gifts; everyone gets them back | yes |
| 25 Dec | One gift per player who logs in + an exclusive cosmetic | yes |
| 26–31 Dec | **Frost raids** at night + a Krampus-style boss | by 26 Dec |
| 31 Dec | Fireworks at midnight, then **"The Betrayal"**: Captain Hale turns traitor (his `/warp` job moves to another NPC) | by 31 Dec |
| from 13 Dec | Empires, armies and siege weapons are out. Player wars use the existing warm-up and battle windows, and siege block damage is always rolled back | 13 Dec |
| January | War phase story: the assault on Hale's ice fortress | January |
| End | **The Crowning**: hall of fame file, statue/banner at spawn, permanent title. `/colonyadmin season end` archives, then wipes colonies, economy and the resource world; titles, cosmetics and Founder badges stay | January |

`/colonyadmin event <name> start|stop` and `/colonyadmin season end` live in ColonySMP and call the SMP season code through a small hook in `ColonyApi`, because ColonySMP can't depend on SMP. **Founder badge**: the first 50 players to found a colony get a permanent "Founder" title (placeholder for chat and TAB).

## 9. Empires, armies, siege weapons (by 13 Dec)

The models and textures start on 26 Nov. I write the `.bbmodel` files and textures from scripts, so you can open and polish them in Blockbench. The code follows in 7–13 Dec.

- **Empires**: a Kingdom can take vassals (sworn or won in war, tribute % in config) and found outposts. Empire menu, laws, particle borders, titles (Emperor/Empress, King/Queen, Lord/Lady, Knight) as placeholders, diplomacy needing acceptance (war excepted), and leaderboards as placeholders. ColonySMP's existing diplomacy and war are the base. No web map is installed; I'll add BlueMap markers only if you install BlueMap.
- **Armies**: squads already exist in ColonySMP (`military/`). Added: a command-banner item, march orders across the map (war only), and army caps per tier.
- **Siege weapons** (ballista T3, catapult T4, cannon T5): Blockbench `.bbmodel` files with load/fire/recoil animations, built into the existing resource pack pipeline (`build_pack.py`, hosted on raw.githubusercontent.com because Minehut only allows certain hosts). My recommendation for showing them: **item_display entities with custom models from our own pack**. No new plugin and no licence cost, and animation is done by moving the display parts. The alternative is BetterModel (free, renders `.bbmodel` animations directly, but it's one more plugin to keep updated). ModelEngine costs money and isn't needed. **Bedrock**: Minehut sends Bedrock players no pack, so they see a block_display version built from vanilla blocks.
- **Block damage only during declared wars and raids.** Every block change is recorded and put back after the war or raid ends, using the record-and-restore approach ModdedrealmsSMP already uses for disasters. CoreProtect isn't installed on the SMP.
- **Settle on Battles**: plan only. Both sides are sent to `arena` over the plugin-message channel, and the result comes back through a shared table in the existing MariaDB.

## 10. Config, messages, flags

- `config.yml` (ColonySMP) gains `features:` with every flag (`onboarding`, `starter-camp`, `funnel`, `winter-needs`, `tiers`, `raids`, `empires`, `armies`, `siege-weapons`, `season-events`), `debug:`, `onboarding:`, `tiers:`, `winter:` and `siege:`. Every value is commented and no number lives in code.
- `messages.yml` (MiniMessage) holds **all new and changed text**. The two plugins have thousands of existing hard-coded lines. Moving every one before launch would take days away from the features, so older lines move over when their code is touched. I'll list what's left.
- `season.yml` (SMP): dates (Europe/London), advent rewards, goal target, event times, cosmetics.

## 11. Testing and delivery

- **Test server**: a local Paper 1.21.11 on this PC (port 25698) with copies of the live plugins, their data and the spawn region. Mineflayer bots play the first 5 minutes on a script and each step is checked. Real-client checks happen when needed (your 26.2 client through Via). The live server is never used for testing.
- **Git**: branch `claude/colony-overhaul`, stacked on PR #25 (`claude/bedwars2-upgrade`), one commit per step, always compiling, `./gradlew --offline build` and `mvn package` with tests green. One PR per phase. Versions: ColonySMP **3.0.0**, ModdedrealmsSMP **1.13.0**.
- **Deliverables**: `OVERHAUL_PLAN.md` (this), `BUG_REPORT.md`, the jars, the commented `config.yml` / `messages.yml` / `season.yml`, the `.bbmodel` files and pack zip with SHA-1 (13 Dec), `TESTING.md` and `PLAYER_GUIDE.md`.
- At the end of each phase I report what changed, how to test it in-game and what's missing.

## 12. New dependencies

| What | Why | When |
|---|---|---|
| **none required** for launch | Both plugins stay Paper-only, plus the PlaceholderAPI that's already installed (soft-depend) | — |
| Spark (recommended) | profiling | test server now; live if you agree |
| Blockbench (on this PC, optional) | opening and polishing the models | from 26 Nov |
| BetterModel (optional) | only if we choose it over item_display | decide by 27 Nov |

## 13. Risks

| Risk | What I'll do |
|---|---|
| Scope is months of work and there are 23 days to launch, now with empires and siege weapons in it too | Strict priority order, flags, a code freeze on 16 Dec. Models are made in parallel from 26 Nov. Empires and siege weapons get the least testing time, so they're the first to stay behind their flag at launch if they aren't solid. |
| Siege block damage on a live server | Only during declared wars and raids. Every change is recorded and rolled back, the rollback is tested on the local server before the 13 Dec deploy, and there's a global off switch (`features.siege-block-damage`). |
| Advent must work on 1 Dec, 5 days away | It's built first, small and on its own, and tested with a fake clock for all 24 days. Deployed 30 Nov. |
| Restarts drive players away (about 15 a day on 23 Nov) | 4 planned deploys before launch, early mornings, with a 2-minute warning. |
| PR #25 is open and the busy-watch may push fixes to it | This branch rebases onto it. Once you merge #25, the PRs re-target `main`. |
| Changing colony data | A numbered migration, the existing backup at start, and a copy of `plugins/ColonySMP/` before every deploy. |
| Bedrock players get no resource pack | Everything visual has a vanilla fallback. Menus are chest GUIs, which work on Bedrock. |
| Minehut blocks pack hosts | Packs stay on the allowed host (raw.githubusercontent.com). |
| 26.2 clients on a 1.21.11 server | Only 1.21.11 features are used. Models are tested through ViaVersion. |
| Two plugins that know about each other | ColonySMP never depends on SMP. SMP talks to it through `ColonyApi`, and the season hook goes the other way. |

## 14. Open questions (none block Phase 1)

1. **Founder badge**: do the first 50 count from launch day (18 Dec) or from now?
2. **Season end**: which parts of the SMP economy are wiped (coins, bank, businesses, stocks, plots)? Needed by early January.
3. **Spark** on the live SMP: yes or no?
4. **Siege weapon models**: item_display (recommended) or BetterModel? Needed by 27 Nov, since the models start now. I'll go with item_display unless you say otherwise.
5. **Other spawn NPCs** for new players: unlock after the choice with Rowan (suggested) or after founding a colony?
6. **Advent rewards**: should I fill in the defaults, or do you have a list?
