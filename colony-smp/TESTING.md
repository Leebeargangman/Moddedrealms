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
0. With `season.enabled: false` in `season.yml` (the default): as a player, `/advent` ✔ "The advent calendar isn't open
   yet. Check back in December!", `/titles` ✔ "Titles aren't here yet…", and there's no countdown sign. Then console
   `colonyadmin event season start` (until restart) or set `season.enabled: true` and `mrsmp season reload`.
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
Season switch (`season.enabled`) checked 25 Nov at 22:22: off, `/advent` and `/titles` refuse and there's no sign;
`colonyadmin event season start` brings the sign up.
**Not yet tried in game:** dispensers outside a colony can't pour lava in, and how the sign and calendar look on a
Bedrock client.

## Release 2: the first five minutes (onboarding)

### Setup
- ColonySMP `config.yml`: `features.onboarding`, `features.starter-camp`, `features.funnel` = true, and
  `onboarding.sites.centre: "-394, 463"` (the plaza). ModdedrealmsSMP `config.yml`: `onboarding.enabled: true`.
- Give the site finder a minute after start: `/colonyadmin funnel` ends with "sites: N ready".
- Use a player name that has never joined (a new account, or delete its playerdata and its line in
  `plugins/ModdedrealmsSMP/onboarding.yml`).

### 1. Arriving (0:00–0:30)
1. Join for the first time. You stand a few blocks in front of Elder Rowan, facing him.
2. Title "Welcome to ModdedRealms" / "Build your kingdom. Rule an empire."
3. Rowan glows gold, sparks circle him and "⚔ START HERE ⚔" floats over his head.
4. Task bar: "Talk to Elder Rowan · he's glowing gold by the fountain". You still get the starter kit.
5. Right-click any other plaza NPC: "Talk to Elder Rowan first...", and gold sparks point to Rowan.
6. Stand still for a minute: Rowan calls you by name (at most 3 times).

### 2. The choice (0:30–1:30)
1. Right-click Rowan: four short lines, then the menu: Found a Colony (glowing, recommended), Join the Battles,
   Talk with Rowan.
2. Join the Battles (on the live network): you are sent to `arena`. The task bar goes away.
3. Talk with Rowan: his usual story and quests.

### 3. Owning something (1:30–3:00)
1. Found a Colony: within a second or two you are taken to a levelled camp out in the wild, facing north at the
   campfire. Title "Your colony, <name>, has been founded!", totem particles, a sound.
2. The camp: Town Hall core with the storage chest east of it, a white and red tent with three beds (west, door
   facing the core), a spruce woodcutter's hut (east), a lit campfire with two log benches, paths and lantern posts.
3. Three settlers walk in from the north and greet you by name; the one without a job asks for one.
4. Inventory: Colony Charter, Starter Farm, Blueprint Book, Building Marker, bread and torches.
5. Task bar: "Step 1/5: Your Colony Menu".

### 4. The first win (3:00–5:00)
1. Right-click the Colony Charter: the Town Hall menu opens. Step 1 completes ($10). A "Start here ▶" arrow points
   at the glowing Citizens button.
2. Right-click the jobless settler → Change job (glowing) → Woodcutter (glowing, "Pick this one"). Step 2 completes
   ($15 and 8 bread to storage); within a minute they walk to the trees and start felling.
3. Right-click flat grass near the campfire with the Starter Farm: a 5x5 field with water and sprouting wheat, plus a
   scarecrow. If the spot doesn't fit, the nearest one within 5 blocks that does is used. Step 3 completes ($40,
   a Common Key and the Banner of <colony>); the farmer starts tending it.
4. Task bar: "Step 4/5: A Growing Camp · 3/5 settlers".

### 5. Always-on helpers
- One-time action-bar tips the first time you hold the Charter or the Starter Farm, and look at a settler, the
  core or the storage chest.
- Stuck on a step for a minute near the camp: a settler tells you what to do (at most 3 times per step).
- `/colony` opens the colony menu; `/colony help` opens the guide (7 pages); `/colony tasks off|on` hides or
  shows the task bar; `/colony start` gives a ready camp to someone without a colony (once per player).
- Log off for 10+ minutes and back: "While you were away (…): +28 oak log, +5 oak sapling…" and the colony's news.
- Night: a new camp's settlers keep working for its first 30 minutes; monsters ignore them, and ignore the founder
  on their own land during the founding grace. Dying without a bed respawns you at your colony.
- A new camp sends no requests, travellers or daily reports until step 3 is done.

### 6. Funnel and admin
- `/colonyadmin funnel [24h|7d|all]`: each step with count and %; the last line shows the camp-site pool.
- `/mrsmp onboarding`: arrived / talked to Rowan / chose a colony / chose the Battles.
- Placeholder `%mrsmp_funnel_step%`: the furthest step a player reached.

### Last run: 27 Nov 2026, local Paper 1.21.11 with copies of the live plugins, data and spawn region
Six bot players: arrival, title, task bar, Pip's "talk to Rowan first", Rowan's intro and menu, a camp 450–1240
blocks out, greetings by name, steps 1–3 in about 25 seconds, the woodcutter felling and the farmer tending at night,
respawn 13 blocks from the camp, Rowan's nudges at 62 s and 122 s, the away summary, `/colony help`, the Battles
choice, the funnel and the once-per-player camp. The 2 live colonies loaded and moved onto the new steps.
Not covered by the bots: the Battles hand-off through the real proxy, Bedrock players, and how it all looks in a real
client.

## Release 3: the colony core (ColonySMP 2.8.0)

Switches: `features.tiers` and `features.winter-needs` in ColonySMP's config.yml. To test winter outside December to
February, set `winter.months: []` on the test server. `colonies.founding-grace-days: 0` and
`onboarding.camp.busy-minutes: 0` let a new test camp feel the cold at once.

### 1. The menu
- `/colony`: overview at the top (with tiers on, "Camp · tier 1/5" first), then Citizens and jobs (slot 19, with the
  "Start here" arrow on a new camp), Buildings, Resources, Tier, Tasks and goals, Military, Diplomacy; then Economy,
  Construction, Recruit, Warmth (only in winter), Research, Settings, More…; Help in the corner.
- More… has Members, Jobs, Requests, Raids, Today, Milestones, History, Statistics and Back.

### 2. Tiers
- Tier opens the road from Camp to Empire: each tier with what it needs and opens. "Become a Village" lists every
  need with ✔ or ✘; clicking it before they're met says the first thing missing.
- Meet the needs (`/colony admin addcitizen <colony>`, `/colony admin treasury <colony> add 500`,
  `/colony admin setstock <colony> oak_planks 100`, and so on), then click: title "Village", fireworks over the Town
  Hall, the unlock lines in chat, and for everyone "The Camp of X has become a Village!". The treasury and goods are
  paid, land grows from 12 to 22 chunks.
- With tiers off the Tier button is the old "Upgrade the Town Hall".

### 3. Jobs
- The job picker shows Farmer, Woodcutter, Miner, Builder, Guard, Blacksmith, Engineer and Soldier. At Camp:
  Blacksmith and Soldier say "opens when the colony becomes a Village", Engineer "…a City". Soldier opens the
  military class picker. More jobs lists open jobs first.
- A settler who already has a job keeps it whatever the tier.
- Woodcutters and miners knock chips off the block they're on between breaks; settlers carrying goods hold them.

### 4. Winter
- Take all firewood out of storage and let a day pass (`/colony admin simulate <colony> 24`): members are told
  "N of X's settlers are cold", Warmth shows them cold and the hearth out. Their mood has a "Cold" line, later
  "Freezing", and they work slower.
- Put 40 logs in storage, another day: the hearth is lit and warms 5; a house with a campfire, furnace or smoker
  inside warms its own people. Firewood shows how many days are left.
- Colonies in their founding grace or first steps aren't affected.

### 5. Blueprints and land
- `plugins/ColonySMP/blueprints/` is created with Hearth Cottage and Stone Wall and Gate; the log lists them.
  `/colonyadmin blueprint list`, `reload`, `preview <name>` (puts the plan down in front of you),
  `share <colony> <copy>`. The plans show in the Blueprint Book as "a server plan" and can be ordered.
- Claiming or founding on land a WorldGuard region covers is refused, naming the region (live: only `spawn`).

### Last run: 27 Nov 2026, local Paper 1.21.11, one bot player
New camp and steps 1–4 as in Release 2; the new menu and More…; the tier screen with the checklist and the "still
need" message; the Blacksmith/Soldier/Engineer locks; the tier-up with title and server-wide line (land 12 → 22
chunks); Soldier open after it; 5 cold settlers with no firewood, all warm with 40 logs; the cottage preview checked
block by block (door at the front, beds, hearth with furnace, campfire and chimney). No errors in the server log.
Not covered: builders putting up a server plan from the Blueprint Book, a WorldGuard region on the test server, the
look in a real client, and the menu with tiers off.

## Release 4: friends (ColonySMP 2.9.0, SMP 1.16.0)

Switches: ColonySMP `features.friends`, SMP `referrals.enabled`. For a quick test: `friends.together-minutes: 1`,
`referrals.minutes-needed: 2`, and `referrals.same-address-check: false` (bots all come from one address).

1. Player A founds a camp with Elder Rowan.
2. Player B (new) right-clicks Rowan: the menu has "A friend invited me" (slot 4) and "Join a Friend's Colony"
   (slot 13, "1 colonies have players online").
3. B: "A friend invited me" → A's head. B: "Thanks! A invited you", A: "B joined with your invite!". The option is
   gone from B's menu after that.
4. B: "Join a Friend's Colony" → A's colony (tier, settlers, who's online) → asked. A gets "[Let them in] [No thanks]".
5. A clicks Let them in (or `/colony letin B`): B gets a title, a Colony Charter, and is taken to the camp. Rowan
   stops calling B.
6. With both on the colony's land for the set minutes: both "★ You and … have played in … together" with $50 and a
   key. Once only.
7. When B has played the set minutes: both get the invite rewards.
8. `/invite` shows your code; `/invite <code>` of the player you invited is refused ("can't invite each other").
9. `/mrsmp onboarding`: "… joined a friend", "Referrals on · 1 invited players, 1 paid out".

### Last run: 27 Nov 2026, local Paper 1.21.11, two bot players
Steps 1–7 and 9 as above: B was taken 618 blocks to A's camp, landing 5 blocks from A; both got the played-together
reward after a minute and the invite rewards at two minutes. No errors in the server log. Not covered: step 8, the
same-address refusal (bots share an address), a reward owed to an offline referrer, and Bedrock players.
