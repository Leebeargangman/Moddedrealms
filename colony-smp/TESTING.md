# Testing the overhaul in game

Each section is one release. Run them on a test server, never the live one. Commands in `code` are typed in game
(as staff) or in the console. "✔" is what should happen.

Results of the last run are at the end of each section.

---

## Release 1 (30 Nov): stability, admin tools, advent calendar

### Setup
1. Put `ColonySMP-2.6.0.jar` and `ModdedrealmsSMP-1.13.0.jar` in `plugins/` (remove the old ones).
2. Start the server. ✔ No errors in the log. `plugins/ModdedrealmsSMP/season.yml` and `plugins/ColonySMP/messages.yml`
   are created.

### 1. The founding-grant loop is closed
1. As a normal player: `/colony create Testone`, then `/colony withdraw 150`. ✔ You get some metal and gems (the grant).
2. `/colony disband`, click the green confirm button.
3. `/colony create Testtwo`. ✔ "You've already had a founding grant this season, so this colony's treasury starts empty."
4. `/colony withdraw 10`. ✔ "The treasury only has 0 worth of valuables."

### 2. No two guards share a name
1. Stand in the Old Town near the fountain for a minute.
2. Console: `execute as @e[type=minecraft:mannequin] run data get entity @s CustomName`.
   ✔ No "Guard …" name appears twice. With more guards than names you see ones like "Guard Ned Finch".

### 3. New colonies can work straight away
1. Go 400+ blocks from the fountain, `/colony create Toolcheck`, and place the Town Hall Core.
2. ✔ "Your first 4 settlers have arrived … They brought a few tools (in the armoury)".
3. `/colony admin citizen Toolcheck <farmer's name>`. ✔ "Carries: stone hoe" (the forester gets the axe).

### 4. Admin tools
- `/colonyadmin` ✔ the list of tools.
- `/colonyadmin debug <colony>` ✔ owner, chunk state, NPCs against the caps, jobs, stores, buildings, tasks, timings.
- `/colonyadmin cleanup` ✔ "No duplicate or orphaned NPCs found" (or a list, then `cleanup confirm` removes them).
- Break `plugins/ColonySMP/messages.yml` on purpose (add a line `x: [broken`), then `/colonyadmin reload`.
  ✔ "Not reloaded, nothing changed: messages.yml: …". Fix it and reload again: ✔ "Reloaded".
- `/colonyadmin raid <colony>` ✔ a raid starts (if raids are on). `/colonyadmin tier <colony> 3` ✔ Town Hall level 3.
- `/colonyadmin give <player> oak_log 64` ✔ 64 oak logs in that player's colony storage.

### 5. Advent calendar
1. Console: `advent date 2026-12-05` (pretend it's 5 December; it resets on restart).
2. As a player: `/advent`. ✔ A Christmas tree of 24 doors under a star. Door 5 is a glowing chest; doors 1–4 are grey
   ("missed"); 6–24 are green with their number.
3. Click door 5. ✔ Chat "Door 5: Lanterns for the road · 8 Lantern, 32 Torch", a chime, snowflakes; the door turns into
   a lantern ("Opened ✔"). ✔ You got 8 lanterns and 32 torches.
4. Click door 5 again. ✔ "You've opened that one." Nothing more is given.
5. Click a green door. ✔ "Door 7 opens on 7 December." Click a grey one. ✔ "That door was open on 3 December."
6. Restart the server, `advent date 2026-12-05`, `/advent`. ✔ Door 5 is still opened.
7. `advent date 2026-12-01` and open door 1 with another player. ✔ "New title unlocked: ❄ Snowflake".
   `/titles`, click it. ✔ "Now wearing ❄ Snowflake". Console `papi parse <player> %mrsmp_title_plain%` ✔ "❄ Snowflake".
8. `advent stats` ✔ how many players opened which doors. `advent date off` back to the real date.

### 6. Countdown
- ✔ A floating sign above the north side of the plaza: "❄ THE FROZEN CROWN ❄ / Season 1 begins in 22d 21h 30m 10s",
  plus "Today's advent door is open: /advent" during 1–24 December.
- Console `papi parse --null %mrsmp_season_countdown%` ✔ e.g. "22d 21h 30m".
- `/colonyadmin event countdown stop` ✔ the sign goes; `start` brings it back.

### Last run: 25 Nov 2026, local Paper 1.21.11 with copies of the live plugins and data
All of 1, 2, 3, 5 and 6 passed with test bots. In 4, everything except `raid`, `tier` and `give` was run.
**Not yet tried in game:** dispensers outside a colony can't pour lava in, and how the sign and calendar look on a
Bedrock client.
