# ColonySMP

Colony simulation for Paper servers. Players found a settlement, claim land around its Town Hall, and run
a population of villager citizens who live, work, eat, sleep, learn, fall ill, get married, grow old,
trade, and go to war. Buildings are built by hand (or by your builders from a plan) and only count once
they pass inspection. Every good in the colony is real: harvested, made, carried and stored by its people.
Nothing appears from nothing.

There is always something going on. Citizens ask you for things, researchers work through a research tree,
merchants' caravans and refugees turn up at the gates, festivals light the sky, and once the colony is worth
robbing, barbarians and the walking dead come for it at nightfall.

Colonies keep going when nobody is around. Citizens near players walk around as villagers. The rest of
the colony runs as a background simulation at the same pace, so a colony on the far side of the map
still farms, cooks, pays wages and grows.

Beyond the players' own colonies there is a **living world** of NPC civilisations: farming villages, mining
settlements, trading towns, fortified kingdoms, bandit camps and more, each with its own people, ruler, stores
and neighbours. They farm, mine, send caravans to each other, sign treaties, fall out, go to war, suffer
blights and plagues, grow into towns or fall into ruin, whether or not anyone is watching. Their economy has
no money in it at all: players barter ordinary items with their merchants, item for item, and every swap goes
through a crash-safe transaction journal. See [The living world](#the-living-world).

No client mods or resource pack needed.

New to it? Read [TUTORIAL.txt](TUTORIAL.txt) for a walk-through of a player's first hour.

## Requirements

- **Paper 1.21.11** and Java 21. The plugin is compiled and unit-tested against the Paper 1.21.11 API, and was
  run on a Paper 1.21.11 server (build 132) for this release: see [What was tested](#what-was-tested).
- The database is SQLite, which Paper already ships. There are no other dependencies and no server internals
  (NMS) are used.

## Install

1. Put `ColonySMP-2.3.0.jar` in `plugins/`.
2. Start the server. `plugins/ColonySMP/config.yml`, `colonies.db` and `journal.db` are created.
3. Optional: change the config, then run `/colony admin reload`.

Build it yourself with `./gradlew build`. The jar lands in `build/libs/`.

### Upgrading from 2.2

Drop the new jar in. `colonies.db` is backed up, then gains one table (`civ_docs`, schema 3); nothing existing
is changed. `journal.db` is created beside it. Things that behave differently:

- **Workers need real tools.** Farmers, foresters, miners and fishers now do nothing without a hoe, axe,
  pickaxe or rod (`citizens.require-tools`, on by default). Check your stores after upgrading: a colony that
  got by bare-handed will stop until it is given tools. Set it to `false` for the old behaviour.
- **Tools matter more.** Work speed by tier is wider than before and better tools bring in extra produce.
- **Animal keepers keep the gate shut** (see [Animal pens](#animal-pens)).
- **NPC settlements begin to appear** on explored land out of players' sight (`civilisations.generation`).
  Set `civilisations.enabled: false` to run without the living world.
- The new settings use their defaults until you add them to `config.yml`; delete the file to have a fresh one
  written with every comment.

### Upgrading from 2.0 or 2.1

Drop the new jar in. Colonies, citizens and buildings load as they are; the new settings (`raids`, `requests`,
`events`, `milestones`, `christmas`, `citizens.chatter-seconds`) use their defaults until you add them to `config.yml` (delete the file to have a fresh
one written with every comment). Established colonies are awarded the milestones they have already reached on
their first day, and their masons start keeping a little gravel, sand and flint.

### Upgrading from 1.x

The 2.0 database is imported automatically on first start. A backup of the old file goes to
`plugins/ColonySMP/backups/` first. Communes become colonies (founder → owner, commissars →
co-owners, comrades → residents), rectangular claims become the chunks they covered, and the State Chest
becomes colony storage. Citizens keep their names, traits, jobs, schooling and gear. Wars in progress are
not carried over. The old tables stay in the database (`v1_*`) as a record. The 1.x config isn't read:
2.0 writes a new `config.yml`.

## The colony

### Founding and land
`/colony create <name>` gives you a **Town Hall Core** (a lodestone). Place it to found the colony:

- The chunks around it (3×3 by default) become yours, and a storage chest appears beside it.
- Your first settlers arrive, with provisions to last until the first harvest.
- You get a 72-hour peace shield.

Claim more chunks next to your land with `/colony claim`. Each claim costs more than the last, and the
settlement level sets the limit. Colonies must keep a gap of free chunks between them. `/colony map` shows
the land around you.

Inside the territory, outsiders can't build, open containers or use doors unless your roles allow it.
Explosions, fire spread and farmland trampling are blocked, and players can't fight each other unless
their colonies are at war.

### Roles
| Role | May, by default |
|---|---|
| Owner | Everything, including disbanding and handing the colony over (`/colony promote <player> owner`). |
| Co-owner | Everything except editing role permissions and disbanding. |
| Manager | Build, use doors and containers, use storage, manage buildings, jobs and citizens, trade, command the military. |
| Resident | Build, use doors and containers, put goods into storage. |
| Ally | Use doors and gates (members of allied colonies). |
| Visitor | Nothing but walking around. |
| Enemy | A member of a colony at war with yours. |

`/colony settings` lists who may do what, and `/colony settings perm <role> <permission> on|off` changes
the Manager, Resident, Ally and Visitor roles. The permissions are build, interact, containers,
storage_deposit, storage_withdraw, manage_buildings, manage_jobs, manage_citizens, policies, treasury,
trade, military, diplomacy, members, roles, territory and settings.

### The Town Hall
Right-click the core (or `/colony menu`) for the **Town Hall menu**: citizens, jobs, buildings,
storage, economy, policies, military, diplomacy, construction, history, statistics, members,
recruitment, the Town Hall upgrade, and research, requests, raids, today's happenings and milestones. The Town Hall level is the **settlement level** (1 to 5). No
building can be a higher level than the Town Hall.

### Storage
Colony storage is counted in slots, like chests: each slot holds one stack of one good. The Town Hall
gives 27 slots per level, every chest or barrel in a **Warehouse** adds 27 more, and a **Granary** holds
food and crops at 1.5× the slots. The armoury holds tools, weapons and armour as items.

The chest beside the Town Hall opens storage: click goods in your own inventory to put them in, click
storage to take them out. Goods are reserved while they're in use: a builder fetching 20 planks or a
trade offer holding 64 bread takes them out of what is available, so two jobs never count on the same
stack. `/colony storage target <good> <min> [max]` sets stock targets. Workshops make goods below the
minimum first, and merchants sell above the maximum.

## Buildings

Build a building by hand, then place the **Building Marker** (a light blue banner, `/colony marker`)
inside it. The marker works out what the building is from what's inside - a bed makes a House, a lectern a
School, an anvil and a furnace a Blacksmith - and registers it. Place it on farmland for a Farm, inside the
fence for a pen or yard, or on top of a tower for a Watchtower. If the building could be more than one type
(a furnace suits a Kitchen and a Bakery), a menu asks which; if it's nothing yet, it says what the closest
type is missing. Right-click the marker to inspect again, **Shift + Right-Click** it to choose another type,
and break it once the building is registered: the building stays and the marker drops for the next one.
Craft more from any banner, paper and a gold nugget (makes 2).

You can also register by hand: hold the **Blueprint Book**, choose the type (right-click to turn pages,
or `/colony book <type>`) and **Shift + Right-Click** inside it (or `/colony building register <type>`).

A building is inspected straight away and every day after that. A building
that fails inspection stops working and tells you why (`/colony validate`). If it keeps failing for 2 days
it is unregistered.

Six types have **standard plans** your builders can build for you: House, Farm, Warehouse, School, Prison
and Watchtower. Choose one in the book, look at the ground and **Shift + Left-Click**, or use
`/colony construction order <type>`. The builders carry every block from storage. The **Copy Wand** copies
any building you've made into a plan of your own, which the builders can then build again.

Upgrade with `/colony upgrade <id>` once the building has what the next level needs (and the Town Hall is
high enough). Upgrades cost valuables and goods.

| Building | Shape | Needs (level 1) | Workers |
|---|---|---|---|
| Town Hall | core block | | Administrator, Treasurer, the first Builder |
| House | room | 9 floor, bed, door | |
| Inn | room | 16 floor, 2 beds, door, barrel | Innkeeper |
| Warehouse | room | 9 floor, door, 2 chests/barrels | Warehouse Worker |
| Granary | room | 9 floor, door, 2 chests/barrels | Food Distributor |
| Farm | farmland | 9 farmland (small/medium/large: 1/2/4 farmers) | Farmer |
| Animal Pen | fenced area with a gate | 9 floor | Animal Keeper |
| Stables | fenced area with a gate | 16 floor, hay bale | Animal Keeper (level 2) |
| Fishery | room by 20+ water | 4 floor, door, barrel | Fisher |
| Forestry Hut | room | 4 floor, door, chest/barrel | Forester |
| Mine | entrance in natural ground | | Miner |
| Mill | room | 9 floor, door, grindstone | Miller |
| Bakery | room | 9 floor, door, furnace/smoker/campfire | Baker |
| Butcher Shop | room | 9 floor, door, smoker | Butcher |
| Kitchen | room | 9 floor, door, furnace/smoker/campfire | Cook |
| Restaurant | room | 12 floor, door, 2 stairs (seats), barrel | Restaurant Worker |
| Blacksmith | room | 9 floor, door, anvil, furnace | Blacksmith |
| Carpentry | room | 9 floor, door, crafting table | Carpenter |
| Masonry | room | 9 floor, door, stonecutter, furnace | Mason |
| Workshop | room | 9 floor, door, fletching table | Craftsworker |
| Market | room | 12 floor, door, 2 barrels | Shopkeeper |
| Trading Post | room | 9 floor, door, cartography table, chest/barrel | Merchant |
| School | room | 9 floor, door, lectern (bookshelves at level 2) | Teacher |
| Clinic | room | 9 floor, door, bed, brewing stand | Doctor |
| Builder's Hut | room | 9 floor, door, crafting table | Builder |
| Barracks | room | 12 floor, door, 2 beds | (soldiers sleep here) |
| Training Ground | fenced area | 25 floor, 2 hay bales | |
| Archery Range | fenced area | 25 floor, 3 target blocks | |
| Armoury | room | 9 floor, door, 2 chests/barrels/smithing tables | |
| Watchtower | tower 5+ blocks tall | | Guard |
| Prison | room | 4 floor, iron door, bed | Warden |
| Engineering Workshop | room | 9 floor, door, smithing table, crafting table | Engineer |
| University | room | 16 floor, door, lectern, 8 bookshelves | Researcher |
| Library | room | 12 floor, door, 6 bookshelves, 2 stairs (seats) | |
| Tavern | room | 16 floor, door, 4 stairs (seats), barrel, note block or jukebox | Bard |
| Enchanter's Tower | room | 9 floor, door, enchanting table, 4 bookshelves | Enchanter |
| Druid's Grove | fenced area with a gate | 9 floor, 2 mossy stones (mossy cobblestone or mossy stone bricks) | Druid |
| Apiary | fenced area with a gate | 9 floor, 2 beehives or bee nests | Beekeeper |

A room of bookshelves with a lectern could be a School or a University, so the marker asks which. A
University's level decides which research it can do; a bigger Enchanter's Tower lays stronger enchantments.

Higher levels need more floor and more of the same things. The book's hover text and
`/colony building <id>` show exactly what the next level needs.

## Citizens

Every citizen has a name, age, life stage (child, adult, elder), trait, skills, education, needs, mood,
job, workplace, home and bed, family, savings, and a history. Right-click one (or use `/colony citizen`) to
see it all.

**Needs:** hunger, energy, health, social, housing, safety and employment. Hungry citizens fetch a meal
from storage or a restaurant. Tired ones go to bed, the exhausted nap wherever they are, and the starving
lose health. Mood follows the needs, plus food variety, wages and taxes, the work they do, illness,
crowding, grief and the colony's policies. Unhappy citizens work slower, and very unhappy ones riot or
leave.

**A day:** work hours (set by policy), leisure, and sleep from dusk. When there's danger, civilians
shelter, flee, or fight (the emergency policy decides which). A citizen's brain checks its priorities in
order: danger, starving, injured, exhausted, then the time of day.

**They use their heads.** A citizen chased by a monster shouts for the watch and runs somewhere safer, not
just away: behind the nearest guard or soldier, home, or to the Town Hall, whichever doesn't mean running past
the danger. Guards answer the shout at a run, go first for whatever is attacking a citizen, then for a raid's
leader, then for the nearly beaten, and fall back when badly hurt instead of dying where they stand. Archers
with no blade keep their distance and go on shooting. Guards and doctors run to a downed citizen and get them
on their feet. Off the alarm, guards eat, see the doctor and sleep when they're tired, at any hour, and wake
if the alarm goes. A soldier left on a standing order still falls out to eat before starving. The unemployed
carry goods instead of standing about, and a worker with two stacks waiting takes them to storage without
waiting for the shelves to fill.

**They talk.** Citizens speak to members who walk past, about whatever is most on their mind: what is stopping
their work ("I'm stuck: waiting for 6 flour"), that they are starving, unpaid, homeless or ill, the raid
expected tonight, the thing they asked you for, or just the time of day. One remark every 45 seconds or so
(`citizens.chatter-seconds`, 0 for off); `/colony chatter off` silences them for you alone.

**Families:** adults who get on become partners and, with a home, enough food and a good mood, have
children. Children grow up in 5 days (by default), go to school if there is one, and get a job when they
come of age. Elders retire from heavy work, and everyone dies of old age in the end.

**Skills and education:** citizens get better at what they do (and at what they've done before).
Education comes from schools and books. Some jobs need some education: an Administrator needs 35, a
Doctor 40.

**Health:** injuries heal faster in a bed or a staffed clinic. Illnesses come from hunger, crowding and
bad food, spread through shared homes and workplaces, and are cured by doctors with medicine. Quarantine
slows an outbreak but keeps the ill off work.

**Jobs** (35 of them): Farmer, Animal Keeper, Miller, Forester, Fisher, Butcher, Cook, Baker, Restaurant
Worker, Food Distributor, Miner, Blacksmith, Carpenter, Mason, Craftsworker, Engineer, Builder, Merchant,
Shopkeeper, Warehouse Worker, Administrator, Treasurer, Teacher, Doctor, Innkeeper, Warden, Researcher,
Bard, Druid, Enchanter, Beekeeper, Guard, Soldier, Student, Unemployed. Assign them with `/colony assign <citizen> <job> [building]`, the citizen
menu, or let an **Administrator** fill empty places with the unemployed every day.

**The rarer trades:**

- A **Researcher** (education 50) works on the colony's research at a University.
- A **Bard** plays at a Tavern through the working day and the evening. Everyone is happier for the music
  (+4 mood), and the tavern is where the colony gathers after work.
- A **Druid** (education 20) walks the fields from their grove and coaxes the crops on. Fields far from any
  player grow a quarter faster with one druid, a little more with each one after.
- An **Enchanter** (education 45) enchants the colony's gear: the weapons, tools and armour its people carry
  first, then the spares in the armoury. Each enchantment takes about four hours' work and two lapis lazuli
  a level. Tools get Efficiency, swords and good axes Sharpness, bows Power, armour Protection, the rest
  Unbreaking. A level 2 tower lays level 2 enchantments; the Arcane Studies research adds one more.
- A **Beekeeper** collects from the hives of an Apiary when they are full: three honeycomb, or a bottle of
  honey if the apiary has glass bottles. The bees are yours to bring (a merchant sometimes sells them).
- A **Library** needs no staff. Adults read there in the evening and slowly educate themselves (up to 70).

**New citizens:** recruit settlers from the Town Hall (`/colony settler`), take in **travellers** who stop
by (`/colony traveller recruit`), shelter **refugees** when they come to the gates, have children, or win
prisoners over. Every newcomer needs a free bed
and a day's food.

## Production and food

Farms grow wheat, carrots, potatoes or beetroots (`/colony building crop <id> <crop|auto>`). Animal pens
raise the animals inside them for eggs, wool, milk and meat. Fisheries fish, foresters fell and replant,
miners dig a staircase down and branch out for ore. Workshops turn inputs into outputs by **recipe**:
flour, bread, cooked meat, meals, planks, tools, weapons, armour, glass, bricks, arrows, books, medicine,
siege charges. Masons also crush cobblestone to gravel, gravel to sand, and sift gravel for flint, keeping a
small stock of each. `/colony building recipe <id> <recipe> on|off` turns a recipe off.

Workers carry what they need from storage and bring what they make back. Warehouse workers and food
distributors do the hauling for everyone else once the colony has them. Crops only grow in the world
where vanilla grows them (near a player). A colony far from players runs its farms, pens, fisheries and
workshops in the background from the same inputs. Jobs that need the world (forestry, mining,
construction) wait until someone is near.

`/colony production` shows what was made and what's waiting, and `/colony resources` shows the stores.

### Tools

Nobody is handed a tool from nowhere. A tool reaches a worker in one of three ways:

- it is **in the colony's stores** (put it in through the storage chest, or have a caravan or merchant bring
  it): the worker walks there and takes the best one of the right kind;
- a member **hands it over**: sneak and right-click the citizen with the tool in your hand. They take it if
  their job uses that kind and it is better than what they carry, and give you their old one back;
- it is **made**: a Blacksmith forges iron tools, a Workshop makes fishing rods, from real materials.

Farmers (hoe), foresters (axe), miners (pickaxe) and fishers (rod) cannot work without theirs; they wait at
their workplace and say what they need. An animal keeper needs shears only for shearing. A new colony's
settlers arrive with food and seed but no tools, so the first job is to make or bring a hoe and an axe.

The better the tool, the better the work:

| Tool | Speed | Extra produce |
|---|---|---|
| Wood | ×0.75 | none |
| Stone | ×1.0 | +5% |
| Gold | ×1.3 (and wears out fast) | +5% |
| Iron | ×1.3 | +12% |
| Diamond | ×1.6 | +22% |
| Netherite | ×1.9 | +30% |

Each level of Efficiency adds 0.12 to the speed and each level of Fortune 10% to the extra produce. Tools wear
out with use and break. `citizens.require-tools: false` lets bare hands work at `citizens.bare-hands-speed`.

### Animal pens

A keeper's animals stay behind the fence:

- Every animal counted inside the fence is marked as the pen's. Only the ground inside the fence counts, not
  the pen's bounding box, so an L-shaped yard works.
- The keeper waits and watches from well inside the pen instead of wandering in and out of the gate.
- **The gate.** Any citizen opens a fence gate only from right beside it, and only if no loose animal is
  within 2.5 blocks. If animals are there, the citizen shoos them back and waits up to two seconds, and if
  they still won't move goes through without opening the gate at all. An open gate shuts the moment the
  citizen is through, and at once if an animal comes within 1.6 blocks of it. It never stays open because an
  animal is standing in it, and an animal caught in the gateway as it shuts is put back inside.
- If an animal does get out (a player left the gate open, a fence was broken), the keeper drops everything,
  fetches it from up to 24 blocks away and leads it back.
- To take an animal out for good, lead it away on a lead or name it: the keeper stops counting it.

`/colony admin pen <colony> <building id>` shows how many of a pen's animals are inside and outside its fence
and how its gates have been handled since the server started.

## Research

Build a **University**, give it a **Researcher**, and choose a project with `/colony research` (or the Town
Hall). Each project costs valuables and goods to begin, takes researcher hours to finish, and then changes the
colony for good. One project at a time; some need an earlier one, and the later ones need a bigger University.
Research carries on while nobody is near.

| Branch | Project | Needs | What it does |
|---|---|---|---|
| Farming | Crop Rotation | | Farmers, animal keepers, fishers, beekeepers and druids work 15% faster |
| Farming | Irrigation | Crop Rotation, University 2 | Fields yield 20% more and grow 20% faster while nobody is near |
| Industry | Improved Tools | | Miners, foresters and builders work 15% faster |
| Industry | Guilds | Improved Tools, University 2 | Everyone who works a recipe works 15% faster |
| Industry | Storehouses | Improved Tools, University 2 | Storage and granaries hold 25% more |
| Industry | Bookkeeping | | Building upkeep costs 15% less |
| Society | Herbal Medicine | | Wounds heal faster; illness is 40% rarer |
| Society | Hardy Folk | Herbal Medicine, University 2 | Every citizen has 4 more health |
| Society | Pedagogy | | Schools and libraries teach 30% faster |
| Society | Arts and Festivals | Pedagogy, University 2 | +3 mood for everyone; festivals come more often |
| Society | Hospitality | | Travellers and merchant caravans visit half again as often |
| Knowledge | Scholarship | Pedagogy, University 2 | Researchers work 25% faster |
| Knowledge | Arcane Studies | Scholarship, University 3 | Enchanters lay enchantments one level stronger |
| Defence | Drill | | Soldiers train 50% faster |
| Defence | Weaponcraft | Drill, University 2 | Guards and soldiers hit and shoot 20% harder |
| Defence | Watch System | Drill, University 2 | Raids are a quarter smaller |

`/colony research list` shows what each costs. Most want paper or books, which a Workshop makes from sugar
cane and leather. `/colony research abandon` drops the project under way (what was paid is lost).

## Requests

Now and then a citizen asks the players for something the stores can't give them: a pie, a few torches, feed
for the animals, strings for the bard. `/colony requests` lists who wants what and why. Bring it and
right-click the citizen with it in your hand (or use the link in the list). They pay you from their own
purse if they have money, cheer up, and work 15% faster for a couple of days; the colony's stability and
reputation rise a little too. Food is eaten on the spot and anything else goes to the stores. A request
nobody answers lapses after three days, and the citizen is a little disappointed.

## Happenings

Every few days, while a member is online, something happens. `/colony today` shows what is going on.

- **Festival**: a contented, well-fed colony declares a feast day. Mood rises for two days, and there are
  fireworks over the Town Hall at dusk.
- **Good growing weather**: fields give a third more for three days.
- **Merchant caravan**: a merchant with a llama walks to the Town Hall and trades for a few minutes.
  Right-click them. They sell livestock (handed over on a lead, to be walked home), bees, and things the
  colony can't make: sugar cane, saplings, books, lapis, bookshelves, an enchanting table, beehives, mossy
  stone, saddles. Anyone can buy, paying in valuables.
- **Refugees**: two or three people ask for shelter. Take them in (`/colony refugees accept`) if there are
  beds and food, or turn them away at a cost in reputation.
- **Lucky strike**: the miners break into a pocket of diamonds, emeralds, gold, lapis, amethyst or redstone.
- **Stray animals** wander into a staffed animal pen.
- **Travelling scholar**: a few hours of free research, or a lesson for the school's pupils.
- **Storm damage**: during a storm, a building may be damaged. Engineers mend it.

## Raids from the wilds

Once a colony has six citizens and is five days old, it is worth robbing. On the morning of a raid, scouts
report who is coming and roughly how many. The raid arrives at nightfall and lasts until the raiders are
dead or the sun comes up. Nobody in the colony can sleep that night.

- **Barbarians** make for the stores. Those who reach the storage chest or a warehouse carry goods off.
  Kill every one of them and the goods come back. They can be bought off beforehand with
  `/colony raid tribute` (it costs the treasury, and a little stability).
- **The undead** come to kill, led by a **Necromancer** who keeps raising more. Kill the Necromancer and
  the dead still standing fall with them.

Raiders are ordinary hostile mobs, so guards, soldiers and (under the militia policy) armed civilians fight
them, and other civilians flee. Bigger, richer, more successful colonies draw bigger raids; the Watch System
research thins them. Beating a raid pays a bounty into the treasury and lifts stability, reputation and
mood. Losing goods or citizens to one does the opposite. The chief and the necromancer drop loot of their
own. Downed barbarians can be bound with rope and imprisoned like any other enemy.

With their chief dead and three quarters of the band down, the last barbarians break and run, dropping
what they carried.

Raids only happen while a member is on or near the colony's land and the colony is loaded, never while a
war is on, and never in a peaceful world. `/colony raid` shows what the scouts know.

## Christmas

From 1 December to 6 January (by the server's calendar; `christmas.mode` can force it on or off) it is the
Christmas season.

- **The tree.** Craft a **Christmas Tree Sapling** (spruce sapling + gold nugget + red dye), or take the
  colony's free one with `/colony christmas`, and plant it on dirt or grass under open sky in your land. It
  grows at once into a spruce with lanterns hung in its branches and a star on top. One tree per colony per
  season; if the star is removed, the tree no longer counts.
- **While it stands:** everyone gets +3 mood, snow drifts round it, half the colony spends the evening
  there, the children play by it and throw snowballs (at you too; they hurt nobody), and the bard at the
  tavern plays carols.
- **Father Christmas** walks in with his reindeer every few in-game days while a member is online. He leaves
  treats in the stores and presents for the children (a mood boost for three days), and every player who
  right-clicks him gets a **Christmas Present**. Right-click the present to unwrap it: cookies, emeralds,
  iron, gold, diamonds, a golden apple, a saddle, a rare enchanted book... or a lump of coal.
- **Also in season:** citizens ask for festive things (cookies, a cake, wool for stockings, snowballs for the
  children), feast days become Christmas feasts with red, green and white fireworks, and people wish you a
  merry Christmas as you pass.

## Milestones

`/colony milestones` lists seventeen marks along a colony's way: ten citizens, a first raid beaten off, a first
discovery, a bard in the tavern, 25 requests answered. Each is rewarded once with a grant to the treasury and
a little reputation.

## Economy

There is **no money** anywhere in the plugin. A colony's **treasury** is the valuables in its storage: iron,
gold and copper (nuggets, ingots and blocks), emeralds, diamonds and netherite ingots. Every cost is a *worth*
measured against the iron nugget (1): an iron ingot is 9, a gold ingot 18, an emerald 10, a diamond 60 (the
`economy.prices` values). Paying takes the real items, largest first, breaking an ingot or block into change
(the vanilla recipes); if only gems are left and none fits, the smallest is handed over without change.
Income (market sales, fines, meals, bounties) arrives as a mix of metal and gems. Smiths that use iron from
storage are spending the treasury too. The full ledger is under `/colony economy`. Under the default **wages**
economy, workers are paid daily (tax is withheld) and spend their pay at the market and on meals, so it
flows back. A **communal** economy pays no wages and everyone eats from the stores. Buildings and soldiers
cost **upkeep**. If wages or upkeep go unpaid, workers lose heart and then strike, and buildings fall into
disrepair.

- **Trading Post** (with a merchant at work): swap surplus for valuables and valuables for shortages on the
  server market (`/colony trade`; valuables themselves are never traded there). Prices move with supply and demand and recover over time. Auto-sell and auto-buy
  follow your stock targets. Food is never sold or traded below the **food reserve** policy.
- **Market** (with a shopkeeper): citizens spend wages here, and any player can buy from or sell to the
  colony (`/colony shop [colony]`).
- **Players** hand valuables in with `/colony deposit <worth|all>` and take them out with
  `/colony withdraw <worth>` (needs the treasury permission). Players pay at colony markets and caravans with
  the same items from their inventory. Nothing is ever dropped on the ground: a payment whose change wouldn't
  fit is refused. Treasuries from older saves are turned into valuables of the same worth on first load.
- **Other colonies**: trade offers, gifts and recurring supply contracts carried by caravans (see
  Diplomacy).

## Policies

`/colony policies` (or the Town Hall): economy model, wage level, tax rate, working hours, recruitment,
population limit, births, military service, defence posture, emergency response, production priority,
food reserve, rationing, quarantine, auto-sell, auto-buy, and who may trade with you. Every policy says
what it costs and what it does to mood.

## Order

Stability is the colony's mood over time. It falls with hunger, homelessness, unemployment, unpaid wages,
illness, crime, high taxes, war, defeat and harsh policies, and rises with full bellies, good homes,
guards and a working economy. Low stability brings theft (fewer thefts where guards watch), disputes,
protests, strikes and riots, and very unhappy citizens leave. Criminals go to prison if there is a staffed
one, or are fined.

## Military

**Guards** watch towers and walls and keep order. **Soldiers** are recruited by class
(`/colony recruit <citizen> <class>`). There are 28 classes: militia, swordsmen, shieldmen, spearmen,
pikemen, axemen, berserkers, macemen, duelists, bodyguards, archers, longbowmen, crossbowmen, javelinmen,
slingers, light and heavy cavalry, lancers, horse archers, cavalry raiders, mounted swordsmen, medics,
standard bearers, commanders, scouts, sappers and siege engineers. Most need a training building and gear
from the armoury, and cavalry need a war horse broken in at the stables. Soldiers train every day, get
paid, and need barracks beds (4 can serve without barracks).

Combat follows class strengths and counters. Spears and pikes break charges, shields stop arrows from the
front, axes split shields, maces go through armour, cavalry crush the flanks, and crossbows pierce.
Training, morale and formation matter as much as gear. A squad whose morale breaks routs.

**Squads** take orders: defend, hold, follow, patrol, charge, shield wall, spear wall, volley, focus,
flank, retreat, protect, hold fire. Formations: line, column, wedge, square, loose. Give orders from the
Military menu, with `/colony orders <squad> <order> [here]`, or with the **Command Baton**: right-click a
block to rally, Shift + Right-Click for the next order, left-click an enemy to focus it, Shift +
Left-Click for the next squad. `/colony mobilize` stands everyone to arms for a while, at a cost in
stability.

## Diplomacy

Colonies are neutral, friendly, allied, hostile, at war, or in a truce. Their relation score moves with
gifts, trade, treaties, broken promises and wars. From the Diplomacy menu or `/colony diplomacy`:

- **Alliance**: allies defend each other and use each other's doors.
- **Treaties**: non-aggression pact, trade agreement, defensive pact, open borders.
- **Trade offers**: goods and valuables each way. What you offer is set aside until they answer.
- **Supply contracts**: a delivery every day for an agreed price.
- **Gifts**, a **call to arms** for allies, and **peace** with a truce.

Offers wait 30 minutes. Accept with the [Accept] link or `/colony diplomacy accept <colony>`.

## War

`/colony declarewar <colony> raid|siege` (costs valuables, needs a defender online by default, and ends your own
peace shield). After a warm-up:

- **Raid**: a short attack. Raiders standing at the defenders' storage loot it, and both sides score
  for kills and loot. Whoever has the higher score when time runs out wins.
- **Siege**: the attackers raise a **War Camp** (place the War Banner near the defenders' land). They
  win by destroying the defending Town Hall or by cutting the defenders off from their stores until order
  collapses. The defenders win by holding out or by tearing down the camp.

A lost siege costs the losers a share of their goods and treasury valuables as spoils. The loser of any war gets a
peace shield, and blocks broken during the war are put back afterwards.
Downed fighters can be taken prisoner. `/colony peace <colony>` offers a truce, and `/colony surrender`
gives up.

## Prisoners

In a fight, citizens and travellers may go **down** instead of dying. A downed enemy can be bound with
**Rope** and led to one of your **Prisons**, or left for your wardens. Wardens feed prisoners and talk to
them every day. Each prisoner has a resistance that wears down until they agree to join the colony.
Prisoners from another colony can be ransomed or exchanged (`/colony prison ransom|exchange`), or
released.

## Background simulation

Every colony advances on its world's clock. A colony with players around has bodies for its citizens
(up to `max-bodies-per-colony`, nearest the players first). Everyone else, and every colony out of
sight, is simulated in steps: needs, meals and sleep, work and production, hauling, wages, illness,
births and deaths, crime. It's all within a small time budget per tick. Nothing happens while the server
is down, because the world doesn't move either. Long gaps are caught up to `max-catch-up-hours`.

## Items and recipes

| Item | How to get it |
|---|---|
| Town Hall Core | `/colony create`, or `/colony core` if lost |
| Blueprint Book | book + gold nugget + paper (shapeless), or `/colony book` |
| Building Marker (2) | any banner + paper + gold nugget (shapeless), or `/colony marker` |
| Copy Wand | stick + gold nugget side by side, or `/colony wand` |
| Rope (×2) | 3 string in a column |
| Command Baton | stick + gold ingot + red dye (shapeless), or `/colony baton` |
| Christmas Tree Sapling | spruce sapling + gold nugget + red dye (shapeless), or `/colony christmas` in season |
| Christmas Present | right-click Father Christmas when he visits |
| War Banner | red banner + iron sword + gold ingot (shapeless), or `/colony banner` |

## The living world

`/civ` (or `/colony civ`) is the way in: `/civ list`, `/civ map`, `/civ info`.

### Peoples and settlements

Each **civilisation** has a name, a ruler with a character (aggressive, peaceful, greedy, diplomatic,
expansionist, defensive, opportunistic or ambitious), a house and perhaps an heir, a form of government
(monarchy, republic, military dictatorship, tribal council, merchant council, oligarchy or theocracy), ten
fields of learning, a chronicle of what has happened to it, and a standing with every other civilisation and
with every player.

Each of its **settlements** has people, soldiers, a mood, a level (hamlet, village, town, city) and stores.
The stores are the whole of its wealth: there is no treasury. There are twelve kinds:

| Kind | Makes | Needs from elsewhere |
|---|---|---|
| Farming Village | wheat, bread, carrots, potatoes, beef, leather, wool, eggs, hay | iron hoes, planks, stone |
| Mining Settlement | coal, raw iron, copper, gold, redstone, a few diamonds, cobblestone, torches | food, logs, iron pickaxes, sticks |
| Trading Town | paper, books, compasses, leads; holds a little of everything | sugar cane, leather, iron, emeralds |
| Military Settlement | swords, bows, arrows, shields, helmets | iron ingots, sticks, string, flint, leather, food |
| Fishing Village | cod, salmon, cooked fish, kelp, ink, rods, boats | charcoal, planks, bread |
| Industrial Town | iron and copper ingots, iron tools, shears, buckets, glass, bricks, rails | raw iron, coal, sand, clay, sticks |
| Coastal Settlement | fish, sand, glass, clay, boats, charcoal | planks, logs, bread, iron |
| Fortified Kingdom | grain, bread, stone bricks, ingots, swords, timber, books | raw iron, coal, leather, luxuries |
| Bandit Camp | meat, leather; whatever it steals | bread, swords, arrows |
| Isolated Community | grain, timber, wool, honeycomb, charcoal, stone axes | iron tools |
| Religious Settlement | honey, candles, paper, books, bookshelves | gold, lapis, wool, leather |
| Frontier Outpost | logs, planks, charcoal, sticks, leather, flint | bread, iron axes, swords |

Every good is made by workers from inputs, at a daily rate, until the stores are full. A settlement that lacks
an input makes less; one that lacks food goes hungry, grows unhappy and shrinks. Nothing appears from nothing.

### Barter

There is no currency, no balance and no price in coins anywhere in this economy. To trade, **right-click a
settlement's merchant** (or `/civ trade` while standing in the settlement):

1. Click something in your own inventory to offer it. That is what you are paying with.
2. Every good the settlement can spare now shows what it costs in that. Left-click swaps for one, right-click
   for eight, shift-click for a stack.

The items change hands at once. Nothing is ever placed in the menu, so closing it, logging out or dying half
way through leaves everything where it was.

What a thing is worth in a settlement moves with:

- **its stores**: empty stores make a good worth 2.5 times its base, full ones bring it to par, and a glut
  drops it to a third. A settlement stops taking a good altogether at four times what it wants;
- **what the settlement is**: a mining town wants bread and has coal to spare, a farm the reverse;
- **war** (food, iron and arms are dearer), **hunger**, **winter**, and passing events (a blight, a rich seam);
- **its wealth**: rich settlements pay more for luxuries;
- **your standing** with its people: better standing, a smaller merchant's cut.

Every item is priced as it changes hands, so a big swap costs more per item than a small one, and buying a
thing and selling it straight back always loses. Merchants take only plain items: nothing named, enchanted,
damaged or carrying other data. Tools, weapons and armour are traded new. A settlement keeps back food and
the inputs it needs and will not sell them.

`/civ economy [settlement]` shows what a place makes, lacks and has too much of, and what its shortages are
worth there.

### Caravans, contracts and reputation

Settlements trade with each other the same way. A **caravan** (a ship, between two harbours) leaves with real
goods from its home's surplus, barters them at the far end for what its home lacks, and carries that back.
While it is on the road its cargo is in nobody's stores. Caravans passing an outlaw camp can be robbed, and
the goods end up in the camp. Trade agreements make caravans bigger; embargoes, wars, sieges and plague stop
them.

A settlement that runs short posts a **contract**: bring so much of what it lacks and receive so much of what
it has to spare, worth up to a third more than what you brought. Take it and hand it in at the merchant
(`/civ contracts`). The reward is set aside when you take the contract and is paid exactly once.

Fair trade and finished contracts raise your **reputation** with a people (`/civ reputation`); abandoned or
overdue contracts lower it. Below -50 they will not deal with you.

### Diplomacy, war and events

Civilisations warm and cool to each other on their own: by their rulers' characters, by trade, by crowded
borders, by having an enemy in common. As relations change they sign trade agreements, non-aggression pacts,
military pacts and alliances, declare embargoes, and go to war over a border, a faith, a trade route or plain
hunger. A war costs soldiers, swords and bread every day; the side with the upper hand lays siege to the
other's nearest settlement, and it ends in exhaustion or in defeat, with reparations carried off, tribute for
a month, sometimes a settlement changing hands, and refugees at the neighbours' gates. A peace treaty holds
for forty days.

Rulers age and die. A hereditary seat passes to the heir and the dynasty goes on; without one there is a
dispute. Unrest builds where order is low and can end in rebellion, a new form of government, or a civil war
in which a far settlement breaks away under its own name.

Things happen to settlements according to their state: blight, plague, festivals, golden ages, rich seams,
raids from outlaw camps, fires and storms, newcomers, mine collapses, scandals, weddings between houses,
travelling scholars. Each has consequences that play out in the stores and the population. `/civ history`
reads a people's chronicle. News reaches players sparingly (`civilisations.news`): the talk of the whole map
is heard everywhere, the rest only near where it happened, and never more than a line a minute.

Learning advances in ten fields (agriculture, mining, construction, metalworking, military equipment, trade,
medicine, transport, infrastructure, defence), up to level 5 each. A level takes scholars' time and uses up
books from the capital's stores. It changes rates and limits; it never produces an item.

### In the world

Settlements appear on **explored** land, out of every player's sight, a few at a time. The map is divided
into squares (`generation.spacing`); each is considered once, for ever, so walking the same ground again
never makes more. A site must be level, dry, clear of anything built by hand, and well away from spawn,
player colonies and other settlements. What kind of settlement it is follows the land: fishing villages by
water, mines in the hills, traders in the desert, outposts in the woods.

A settlement is a market stall, cottages, and the building it is known for (a field, a mine head, a forge, a
shrine, a store yard), with a watchtower and palisade for the martial kinds, in the wood and stone of its
biome. Each building goes only on natural ground: if its footprint touches anything made by hand, is too
steep or is under water, that building is left out. Settlements add houses as they grow and crumble to ruins
when they fall. A living settlement's buildings cannot be broken, built on, burned, blown up or pushed by
pistons, and its barrels are scenery; ruins are free to explore and take apart.

While a player is within `npc-activation-distance` a settlement shows a **merchant** and a few **residents**
who walk between the houses and the market by day. Right-click a resident to hear what is on the town's mind.
These villagers have no AI of their own, cannot be hurt or traded with the vanilla way, are capped across the
server, and are never saved with the world.

### While nobody is there

A settlement a thousand blocks from anyone is simulated exactly like one a player is standing in: the world
runs on a calendar of days, one per Minecraft day, at about 0.05 ms per settlement per day. While the server
is off the calendar moves on at a quarter speed (`offline-rate`), up to two weeks (`offline-days-max`), and
the days are made up when it starts.

## Transactions and anti-duplication

Every exchange of items between a player and the world goes through one transaction manager. Nothing else in
the plugin gives or takes barter goods.

An exchange happens in one server tick, in this order:

1. **Validate.** The player is online, alive, in survival or adventure, and has no other trade in flight
   (a second click within 250 ms is refused). Quantities are sane. Every item to be given is in the inventory
   as a plain item, and every item to be received fits. If the whole swap doesn't fit it doesn't happen;
   nothing is ever dropped on the ground.
2. **Reserve.** The settlement sets its goods aside so nobody else can be given them.
3. **Prepare.** The whole exchange is written to `journal.db`, durably, with a unique id, a sequence number
   and, for a reward, an idempotency key that can only ever be written once.
4. **Transfer.** The inventory is rewritten to the result worked out in step 1, and the player is stamped with
   the transaction's sequence number. The stamp is stored in the same file as the inventory, so the two are
   saved together or not at all. Items received are fresh stacks; nothing a player hands over comes back.
5. **Commit.** The settlement's books are updated and the journal row is marked committed.

A refusal before step 4 undoes the earlier steps and changes nothing. After a crash:

- Trades committed after the last save of `colonies.db` are **replayed** onto the settlements' books at
  start-up, in order, so a settlement never gets back goods a player walked away with.
- A trade cut short between steps 3 and 5 is **in doubt**. Its goods stay set aside until its player next
  joins. Then the stamp decides: if it is at or past the trade's sequence number the inventory change was
  saved, and the trade is carried through; if not, the change was lost with the crash, and the trade is
  undone. Either way the items exist exactly once, and the player is told in one line.

Minecraft inventories are not part of any database transaction, which is why this is done with a journal, a
reservation and a stamp rather than a rollback.

### Audit

`/colony audit` shows the journal's totals, anything in doubt and the latest notes. `/colony audit player
<name>` (or `/colony admin investigate <name>`) lays out one player: trades by hour and day, worth given and
received, standing, notes and recent transactions. `/colony audit transactions [n]` lists the latest rows and
`/colony audit flags` the notes. `/colony audit resolve <id> commit|rollback` settles an in-doubt trade by
hand for a player who never comes back.

Notes are written when a player receives far more worth than they gave, trades faster than hands can, floods
the merchant with refused clicks, or gains a great deal in an hour, and whenever a crash recovery or an admin
change to a settlement's stores happens. **A note is evidence for a person to weigh. Nothing is ever done to a
player automatically:** nobody is blocked, kicked or banned by it.

## Commands

`/colony` (aliases `/col`, `/colonies`). `/colony help <topic>` lists them in game.

| Topic | Commands |
|---|---|
| Colony | `create <name>` · `info [colony]` · `list` · `map` · `claim` · `unclaim` · `menu` · `core` · `book [type]` · `marker` · `wand` · `baton` · `banner` · `disband` · `quests` · `events [type] [page]` · `stats` |
| Members | `invite <player>` · `join <colony>` · `leave` · `kick <player>` · `members` · `promote\|demote <player> [role]` · `rename <name>` · `settings` |
| Citizens | `citizens` · `citizen <name>` · `jobs` · `assign <citizen> <job> [building]` · `home <citizen> [house]` · `needs [citizen]` · `settler` · `traveller recruit\|dismiss` · `exile <citizen>` · `release <citizen>` |
| Buildings | `buildings` · `building <id>` · `building register <type>` · `building crop\|herd\|recipe\|name\|remove ...` · `validate [id\|all]` · `upgrade <id>` · `construction [order <type> \| pause\|resume\|cancel\|first <id>]` |
| Economy | `resources` · `storage [target <good> <min> [max] \| clear <good>]` · `economy` · `trade [sell\|buy <good> <qty>]` · `production` · `deposit\|withdraw <amount>` · `shop [colony]` · `policies [set <policy> <value>]` |
| Military | `military` · `recruit <citizen> <class>` · `army` · `orders <squad> <order> [here]` · `squad create\|delete\|rename\|add\|remove\|formation\|patrol\|select ...` · `mobilize` |
| Diplomacy | `diplomacy [list\|offers\|accept\|decline\|ally\|treaty\|cancel\|gift\|trade\|contract\|calltoarms]` · `declarewar <colony> raid\|siege` · `peace <colony>` · `surrender` |
| Prisoners | `prison` · `prison release <name>` · `prison ransom <name> [price]` · `prison exchange <ours> <theirs>` |
| Events | `today` · `chatter [on\|off]` · `christmas` · `requests [deliver <n>]` · `research [list \| start <project> \| abandon]` · `raid [tribute]` · `refugees accept\|refuse` · `milestones` |

Citizen names can have spaces: `/colony assign Rhea Pike farmer` works.

**The living world:** `/civ` (aliases `/civs`, `/civilisations`, and `/colony civ`).

| Command | What it does |
|---|---|
| `/civ list` | Every people and its settlements, with coordinates |
| `/civ info [settlement]` | A settlement's menu: people, ruler, learning, friends and enemies, chronicle, economy, contracts (the nearest if none is named) |
| `/civ map` | Settlements around you, with distance and direction |
| `/civ relations [people]` · `/civ diplomacy` | Who is friendly, rivals, allied, embargoed or at war, and their treaties |
| `/civ economy [settlement]` | What it makes, lacks and has to spare; caravans on the road |
| `/civ trade` | Barter with the merchant of the settlement you are standing in |
| `/civ contracts` · `/civ missions` | Deliveries on offer, and yours |
| `/civ reputation` | How you stand with each people |
| `/civ history [people]` | Their chronicle |
| `/civ military [people]` | Soldiers, arms and wars |
| `/civ wanted` | Peoples that have shut their gates to you |

**Admin** (`colonysmp.admin`): `/colony admin reload | save | bypass | give <item> [player] | delete <colony>
| tp <colony> | inspect <colony> | citizen <colony> <name> | building <colony> <id> | reservations <colony>
| production <colony> | sim | perf | db | stuck <colony> | events <colony> | simulate <colony> <hours> |
setstock <colony> <good> <qty> | treasury <colony> set|add <n> | addcitizen <colony> [job] | endwar <colony>
| stability <colony> <n> | shield <colony> <hours> | traveller <colony> | repair <colony> | bodies |
demo <name> [x z] | raid <colony> [barbarians|undead] [size] | event <colony> <kind> |
research <colony> <project|all> | request <colony>`. `raid` starts a raid at once, `event` makes a happening happen (festival, harvest, caravan,
refugees, lucky_find, strays, scholar, storm, santa), `give` also hands out `christmas_sapling` and `present`, `research` grants a project, and `request` has a citizen ask
for something. `inspect` lists every citizen with their position, activity and needs. `simulate`
fast-forwards a colony's background simulation, and `demo` founds a stocked test colony from the console.

**Admin, living world:** `/colony admin civ status | list | inspect <settlement> | tp <settlement> | create
<type> [people]` (players, where they stand) or `create <type> <x> <z> [world] [people]` (console) `| remove
<settlement> [keep] | setstock <settlement> <good> <qty> | pop <settlement> <people> | relation <people>
<people> <-100..100> | war <people> <people> [cause] | peace <people> <people> | event <settlement>
blight|plague|festival|golden|strike|clear | step [days] | generate`. Also `/colony admin register <colony>
<type> <x> <y> <z>` (register a building from the console) and `/colony admin pen <colony> <id>`.

**Audit:** `/colony audit [player <name> | transactions [n] | flags | resolve <id> commit|rollback]` and
`/colony admin investigate <name>`.

**Permissions**

| Permission | Default | Allows |
|---|---|---|
| `colonysmp.play` | everyone | Colonies, `/civ`, bartering and contracts |
| `colonysmp.admin` | op | Every admin tool, the protection bypass, and all the permissions below |
| `colonysmp.admin.audit` | op | Reading the journal: `/colony audit`, `/colony admin investigate` |
| `colonysmp.admin.audit.resolve` | op | Settling an in-doubt transaction by hand |
| `colonysmp.admin.civ.status` `.list` `.inspect` `.tp` | op | Looking at the living world |
| `colonysmp.admin.civ.create` `.remove` | op | Founding and removing settlements |
| `colonysmp.admin.civ.setstock` `.pop` | op | Changing a settlement's stores (noted in the audit log) and population |
| `colonysmp.admin.civ.relation` `.war` `.peace` | op | Changing how civilisations stand, starting and ending wars |
| `colonysmp.admin.civ.event` `.step` `.generate` | op | Bringing on events, moving the calendar, asking for a new settlement |

`/colony admin ...` as a whole still needs `colonysmp.admin`; the finer permissions let you take single
actions away from an administrator with a permissions plugin.

## Configuration

Every number lives in `config.yml`, with a comment on each: claims and land, protection and shields,
citizens and bodies (speeds, work intervals, body limits), life stages and births, needs, education, the
economy and prices, storage, the background simulation budget, health, order, the military, prisoners,
travellers, war, diplomacy, plans, mines, the starter quest reward, raids, requests, happenings,
milestones and Christmas. Raids, requests, happenings and milestones each have an `enabled` switch. `/colony admin reload` applies
changes.

The living world and the journal have their own sections:

| Setting | Default | Meaning |
|---|---|---|
| `civilisations.enabled` | true | The whole living world |
| `civilisations.max-settlements` | 24 | Living settlements at once |
| `civilisations.max-settlements-per-civilisation` | 4 | |
| `civilisations.max-citizens-per-settlement` | 220 | |
| `civilisations.max-military-units` | 60 | Soldiers per settlement |
| `civilisations.max-active-npcs` | 40 | Settlement villagers in the world at once, server-wide |
| `civilisations.residents-per-settlement` | 3 | Walking residents shown per settlement |
| `civilisations.npc-activation-distance` | 64 | Blocks from a player at which a settlement's villagers exist |
| `civilisations.simulation-interval-seconds` | 5 | How often the calendar is checked |
| `civilisations.production-rate` · `growth-rate` · `event-frequency` | 1.0 | Multipliers |
| `civilisations.wars` · `contracts` | true | |
| `civilisations.max-contracts-per-player` | 3 | |
| `civilisations.trade-range` · `caravan-blocks-per-day` | 2000 · 500 | |
| `civilisations.offline-rate` · `offline-days-max` | 0.25 · 14 | How the world moves on while the server is off |
| `civilisations.news` | nearby | `all`, `nearby` or `off` |
| `civilisations.protect-settlements` | true | |
| `civilisations.generation.enabled` | true | New settlements appear on their own |
| `civilisations.generation.only-in-explored-chunks` | true | Never generate new terrain for a settlement |
| `civilisations.generation.interval-seconds` · `spacing` · `density` | 300 · 600 · 0.4 | |
| `civilisations.generation.min-distance-from-spawn` · `-colonies` · `-players` | 250 · 160 · 180 | |
| `civilisations.generation.worlds` · `avoid` | empty | Allowed worlds; `"world,x,z,radius"` places to keep clear |
| `civilisations.worth` | empty | Base worth overrides, e.g. `diamond: 80` |
| `transactions.durability` | full | `full` survives a power cut; `normal` survives a crash |
| `transactions.min-milliseconds-between-trades` | 250 | |
| `transactions.keep-days` | 90 | How long finished rows and notes are kept |
| `transactions.audit.*` | 30 · 20 · 5000 | Trades a minute, refusals a minute and net worth an hour that earn a note |
| `citizens.require-tools` · `bare-hands-speed` | true · 0.3 | See [Tools](#tools) |

Limits, rates and news apply on `/colony admin reload`. `civilisations.enabled` and `transactions.durability`
are read at start-up.

## Persistence

Everything is saved to `plugins/ColonySMP/colonies.db` (SQLite): colonies, members and roles, claims,
storage and reservations, the armoury, citizens, buildings, build orders, plans, squads, relations,
offers, contracts, shipments, wars, the market, research, open requests, milestones, and every transaction
and event. Changes are saved within
30 seconds, every few minutes, and on shutdown, as one transaction on a background thread. If a save
fails it is retried. The database is backed up at every start (`general.backups-to-keep`), and its schema
is versioned so later releases can migrate it.

The living world is saved in the same file and the same transaction, in the `civ_docs` table: one document
each for the world's calendar and relations, every civilisation, every settlement (with its stores and what
is set aside in them), every caravan, contract and war.

`plugins/ColonySMP/journal.db` is the transaction journal: `txn_journal` (every exchange and its state),
`txn_claims` (idempotency keys) and `audit_flags`. It is written synchronously, a row at a time, and is a
separate file so a trade never waits behind a colony save. Keep the two files together when you back up or
move a server. If `colonies.db` is restored from an older backup while `journal.db` is kept, the trades made
since that backup are replayed onto the restored books at start-up.

**Migrations.** `colonies.db` carries a schema number (`meta.schema`): 1 was ColonySMP 1.x, 2 is 2.0 to 2.2, 3
is 2.3. Opening an older database backs it up and adds what is missing; a database from a newer version is
refused with a message rather than guessed at. `journal.db` uses numbered migrations recorded in
`schema_migrations`, each applied in its own transaction, so a failed step leaves the file as the last good
step left it.

Citizen bodies, raiders, visiting merchants and settlement villagers aren't saved with the world. The plugin spawns them near players and removes them when
they're no longer needed, so they never duplicate.

## Admin guide to the living world

- **Seeing what is going on:** `/colony admin civ status` (day, counts, cost of the last simulated day, journal
  position), `/colony admin civ inspect <settlement>` (its full books), `/colony audit`.
- **Founding by hand:** stand where you want it and `/colony admin civ create <type>`; add a people's name to
  make it one of theirs. The site is checked the same way as for generated settlements (with shorter distances)
  and refused with the reason if it is unfit.
- **Keeping places clear:** list them under `civilisations.generation.avoid`. Player colonies, spawn and
  anything built by hand are avoided already.
- **Turning generation off** (`civilisations.generation.enabled: false`) leaves existing settlements running.
- **Performance:** the simulation costs the same wherever players are; what costs entities is settlement
  villagers, bounded by `max-active-npcs`. Lower `residents-per-settlement` or `npc-activation-distance` first.
- **After a crash:** the console reports how many trades were replayed and how many are in doubt. In-doubt
  trades settle themselves when their players join. For a player who is never coming back, `/colony audit
  resolve <id> rollback` frees the goods.
- **Backups:** copy `colonies.db` and `journal.db` together, with the server stopped or right after
  `/colony admin save`.

## Known limitations

This release is the foundation and the first playable part of a larger plan ([docs/ROADMAP-3.0.md](docs/ROADMAP-3.0.md)).
What follows is simulated, partial or not there yet.

- **Wars between civilisations are fought in the books only.** Soldiers, losses, sieges, captures and
  reparations are arithmetic. No armies march, no siege damages a block, and players cannot yet join, supply,
  sabotage or negotiate.
- **Caravans and ships are not visible.** They carry real goods and can be robbed by outlaw camps in the
  simulation, but there is nothing to see, escort or rob on the road.
- **Citizens of NPC settlements are numbers.** A settlement's people are a population with a mood, not
  individuals with names, homes, inventories and relationships. The few villagers shown walk between points;
  they do not work.
- **No crime yet:** no theft, pickpocketing, smuggling, espionage, bribery, bounties or prisons in NPC
  settlements. `/civ wanted` only lists peoples that refuse to trade with you.
- **Missions are delivery contracts only.** No escorts, hunts, rescues, exploration or an adventurers' guild.
- **No ancient ruins or treasure.** Ruins are what is left of settlements that fell during play.
- **Players cannot yet** sign trade agreements, invest in settlements, hold office, or make treaties between
  their colonies and NPC civilisations. Player colonies and NPC settlements do not trade with each other
  except through players carrying goods.
- **Settlement buildings are simple.** A handful of small structures per settlement, no roads between
  settlements, no docks or ships.
- **Player colony treasuries are valuables, not money**, but their payments to and from players (deposits,
  withdrawals, the colony market, caravans) move items directly rather than through the barter journal.
- **Vault is no longer used** at all.
- If the journal's commit for a finished trade cannot be written (a full disk), the trade stands, the write is
  retried every second, and the books remember it across a restart. A full disk *and* a crash before the next
  save of `colonies.db` is the one case that needs an administrator to look at `/colony audit`.

## What was tested

- **Unit tests** (132, run by `./gradlew build` against the Paper 1.21.11 API and a real SQLite file): the
  gate rules and a model pen that reproduces the old escapes and shows none with the new rules; the
  inside-the-fence test at its edges; inventory arithmetic, including 3,000 random swaps checked for
  conservation; the journal's whole life cycle, duplicate rewards from eight threads at once, simulated
  crashes before and after the inventory was saved, replay after a save, pruning and migrations; barter
  pricing, including that buying and selling back, or swapping round three goods, never gains; and the world
  simulation: a farming village feeding itself, a mining town that cannot, trade fixing that, nothing coming
  from nothing, stores filling and stopping, the same seed giving the same history, everything surviving a
  save and a load, wars begun, restarted through and ended, succession, ruin, contracts, learning, and 40
  settlements simulated for a year in about 2.5 ms a day.
- **On a live Paper 1.21.11 server** (build 132, no players, chunks force-loaded so a colony's citizens walk and
  work):
  - The plugin enables, saves and shuts down cleanly, creates a 2.3 `colonies.db` and `journal.db`, and loads
    them again across four restarts.
  - **The animal keeper.** A demo colony, a 9×9 fenced pen with one gate, 17 animals and a keeper were set up
    from the console. Over a 12-minute run sampled every 30 seconds (24 samples) the keeper went out for
    shears and feed, bred, sheared and came back; the gate was opened 27 times and twice the keeper waited
    for animals to clear it; the herd grew to 26; and **none of the pen's animals was outside the fence in
    any sample**. The harder cases (slipping through a shut gate, the gate shutting on an approaching animal,
    an animal put back from the gateway, a stray fetched) did not come up in that run and are covered by the
    unit tests only. The run also found a fault that was then fixed: a small animal pressed against the
    inside of the fence was being counted as outside.
  - **Settlements.** Five of different kinds were founded from the console and built in real terrain (6 to 10
    structures each); a sixth site was refused as unfit. The world was advanced 42 days (about 2 ms per day
    for five settlements); caravans ran, and the farming village ended up holding coal, copper and
    cobblestone it does not make. Growth, events and contracts were seen in the books.
  - **A crash.** The server process was killed outright four simulated days after its last save. It came
    back at the last saved day with every settlement, its stores and the pen intact, the journal empty of
    doubt, and no errors.
- **Not tested on a live server**, because it needs a real player and none was connected: the barter menu, the
  settlement menu, contracts taken and delivered by a player, the in-game path of the transaction manager
  (its arithmetic and its journal are unit-tested; the few lines that touch a real inventory are not), crash
  recovery with a real player's stamp, settlement villagers appearing near a player, protection against a
  player breaking settlement blocks, automatic generation around players, and handing a tool to a citizen.
  Try these on a test server before relying on them.
