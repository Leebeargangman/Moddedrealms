# ModdedrealmsSMP feature log

One line per shipped feature, newest last. Read this before adding a feature so nothing is repeated.
(The daily-feature task's instructions say `smp-features/`, but the live `ModdedrealmsSMP` plugin already lived
here in `minecraft-smp/`, so its features are logged here to avoid two plugins with the same name.)

| Version | Date | Feature | Commands / permissions | Config |
|---|---|---|---|---|
| 1.0.0 | 2026-11-22 | NPC quest givers, story, daily/weekly quests, crates with virtual keys, playtime rewards, welcome, spawn plaza | `/quests`, `/crates`, `/mrsmp`, `/mrnpc` · `mrsmp.quests`, `mrsmp.use`, `mrsmp.admin` | `rewards`, `quests`, `playtime`, `welcome` |
| 1.2.0 | 2026-11-22 | Town: police, townsfolk, reputation, jail | `/mrtown`, `/rep`, `/bail`, `/surrender` · `mrsmp.lawless` | `town` |
| 1.3.0 | 2026-11-22 | Bounties, player police, bandits, Bureau, bank, community service, bribes | `/police`, `/tip`, `/bounty`, `/bounties`, `/bribe`, `/service`, `/wanted` | `town` |
| 1.4.0 | 2026-11-22 | Casino with raids, gangs, NPC gangs, heists, prison breaks | `/casino`, `/dice`, `/heist`, `/gang`, `/gc` | `town` |
| 1.5.0 | 2026-11-23 | Meadowbrook town, plots, the Meadowbrook Line railway | `/plot`, `/ticket` | `town` |
| 1.6.0 | 2026-11-23 | Businesses, supply chains, jobs, bank loans | `/business`, `/jobs`, `/job`, `/loan`, `/herald` | `town` |
| 1.7.0 | 2026-11-23 | **Graves**: death drops + XP wait in a player-head grave with a floating label; owner-only for 15 min; items go back into their original slots | `/graves` (`mrsmp.graves`, default true); `/graves all\|tp\|return\|remove <id>` and opening any grave (`mrsmp.graves.admin`, op) | `graves.enabled`, `unlock-minutes`, `expire-minutes`, `store-xp`, `hologram`, `search-radius`, `disabled-worlds` |
