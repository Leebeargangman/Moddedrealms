# ColonySMP 3.0 "Living Civilisations": implementation plan

This is the working plan for turning ColonySMP into a living-world civilisation simulation. It records what
the existing plugin looked like when the work began, the decisions taken, what 2.3 delivers, and what each
later release is to add. Nothing here is a deadline; the order is the order that keeps every release stable.

## 1. What was there (2.2.0)

About 34,500 lines of Java in one Gradle module: player colonies with territory, 38 building types, 35 jobs,
production chains, research, raids, requests, random events, milestones, a money economy, diplomacy and war
between player colonies, prisoners, and a background simulation for colonies nobody is near.

What was sound and is built on:

- **A pure simulation core** (`com.colonysmp.core`) with no server classes, unit-tested: goods, a stock ledger
  with reservations, a treasury, recipes, combat arithmetic, seasons.
- **Bodies are not the people.** Citizens are records; a villager exists for one only while its colony is
  loaded. The same idea carries over to NPC settlements.
- **Snapshot saves.** The whole live state is written to SQLite as one transaction on a background thread,
  with a backup at every start.

What limited it:

| Limitation | Consequence | What 2.3 does |
|---|---|---|
| No journal. A treasury change and an inventory change were two separate acts, saved at different times. | A crash between them could duplicate or lose items; nothing recorded what was meant to happen. | A write-ahead transaction journal with reservations, idempotency keys and crash recovery (`com.colonysmp.txn`). |
| Player money was paid out by dropping what didn't fit on the ground, and taken without a record. | Fine for a treasury; not a base for trading items with NPCs. | Barter never drops anything and never half-completes: the whole swap is planned on a copy first. |
| The world outside player colonies was empty. Merchants and raiders were events, not societies. | Nothing happened unless a player colony caused it. | `com.colonysmp.civ`: civilisations simulated day by day, independent of players and chunks. |
| The schema was "create tables if missing" with a version number. | No ordered, recorded migrations. | Numbered migrations for the journal; the colony database goes to schema 3 by adding one table. |
| Simulation was per player colony, at citizen level. | Too heavy to run for dozens of NPC settlements. | NPC settlements are simulated in aggregate (population, stores, mood), about 0.05 ms each per day. |
| One command class of 1,700 lines, one permission for all admin tools. | Hard to extend, no fine control. | A separate `/civ` command and a permission per administrative action. |
| Built against Paper 1.21.4. | Not the target version. | Built and tested against Paper 1.21.11. |

## 2. Decisions

**Barter only.** The NPC economy has no currency, no balance and no price in coins. A good has a *worth*,
which is nothing but an exchange rate: how many of one item a settlement gives for another. Nobody holds any.
Settlements have no treasury; their wealth is their stores. Player colonies have no money either: their
treasury is the iron, gold, copper, emeralds, diamonds and netherite in their storage, valued against the
iron nugget, and paid out as those items with ingots and blocks broken for change.

**One door for items.** Every transfer of barter goods between a player and the world goes through
`TransactionManager.exchange`. Menus and commands only build requests.

**Inventories are outside the database, so design for it.** The order is: plan on a copy, reserve the other
side, write the journal row durably, change the inventory and stamp the player in the same tick, update the
other side's books, mark the row committed. The stamp (the transaction's sequence number, stored in the
player's own data file with the inventory) is what resolves a row left in doubt by a crash: saved stamp at or
past the row means the items moved; otherwise they did not. The colony database remembers the last journal
commit it contains, and later commits are replayed onto it at start-up.

**Items never rest between the two sides.** No offer slots, no ground drops, no delivery queues. That removes
menu-close, disconnect, death, hopper and pickup exploits by construction rather than by listening for them.

**Only plain items.** Merchants take items with no metadata at all and hand out fresh stacks, so nothing a
player crafted into an item's data survives a round trip.

**The world is arithmetic.** A day of the living world is a pure function of its state and a seed. It runs the
same for a settlement next to a player and one nobody has seen for a month, can be replayed, and is tested
without a server. Chunks are never loaded to simulate.

**Nothing from nothing.** Goods enter the world only as an industry's daily output, bounded by workers, inputs
and full stores. Events, treaties and technology change rates; they never grant items. Contract rewards come
out of the settlement's own stores and are capped at a third over what was delivered.

**Evidence, not punishment.** The audit writes notes. It never blocks, kicks or bans.

**Safe by refusal.** A settlement building is only placed on natural ground. When in doubt (an unknown block,
steep or wet ground, a colony nearby) the building, or the whole site, is skipped.

## 3. Architecture after 2.3

```
com.colonysmp.core    pure   goods, ledger, reservations, treasury, recipes, GatePolicy
com.colonysmp.txn     pure*  Journal (SQLite), Migrations, InventoryMath, TradeGuard, AuditRules
                             TransactionManager (the only class here that touches the server)
com.colonysmp.civ     pure   TradeGoods, SettlementType, Government, Personality, Tech, Leader, Civ,
                             Settlement, Shipment, Contract, War, Treaty, CivWorld, Prices, CivSim, CivFactory
com.colonysmp.store          Database (schema 3), Snapshot, CivCodec
com.colonysmp.world          CivManager, SettlementBuilder, SettlementGenerator, SettlementBodies,
                             SettlementProtection
com.colonysmp.gui            BarterMenu, SettlementMenu (beside the existing menus)
com.colonysmp.command        CivCommand (beside ColonyCommand and AdminCommand)
```

Files: `colonies.db` (everything, including the living world in `civ_docs`) and `journal.db` (the journal,
idempotency keys and audit notes).

## 4. Releases

Status: **done** is in 2.3 and tested as described in the README; **partial** is in 2.3 in a reduced form;
**planned** is not started.

### 2.3 Foundations and the first playable world (this release)

| Area | Status | Notes |
|---|---|---|
| Paper 1.21.11 build, Java 21, no NMS, no new dependencies | done | |
| Transaction journal, reservations, idempotency, recovery, replay | done | Unit-tested against SQLite, including simulated crashes. The in-game path needs testing with a real player. |
| Anti-exploit: double clicks, concurrent trades, full inventories, invalid quantities, metadata, death, duplicate rewards | done | By construction; see section 2. |
| Audit commands and notes | done | `/colony audit`, `/colony admin investigate`. |
| Schema migrations | done | Journal: numbered. Colony database: schema 3. |
| Civilisations: identity, government (7), leader personality (8), dynasty and succession, unrest, rebellion, civil war | done | In the books. |
| Settlements: 12 kinds, population, soldiers, mood, levels, growth, decline, ruin | done | |
| Production chains, consumption, shortages, surpluses, specialisation | done | |
| Dynamic barter pricing; bulk trading; merchant menu | done | |
| Settlement-to-settlement trade by caravan and ship, with real cargo and robbery | partial | Simulated; nothing visible on the road. |
| Delivery contracts with reputation requirements, deadlines, failure | done | The only mission type so far. |
| Reputation per player per civilisation | done | |
| Diplomacy: relations, trade agreements, non-aggression, military pacts, alliances, embargoes, tribute, peace | done | Between NPC civilisations only. |
| War: causes, daily battles, sieges, capture, reparations, refugees | partial | Abstract only. |
| Events with consequences (12 kinds) | partial | Of the 28 listed in the brief. |
| Technology: 10 fields, 5 levels, needs time and books | done | |
| Settlement generation: terrain-aware, once per map square, explored land only, out of sight | done | Needs a live test with players moving about. |
| Settlement buildings, growth, decay to ruins, protection | partial | Small structures; no roads, docks or interiors of note. |
| Merchant and resident villagers near players, capped | partial | Residents walk; they do not work or have identities. |
| Offline catch-up | done | |
| Animal keeper gate fix; tools must be real and matter | done | Live-tested (keeper), unit-tested (rules). |

### 2.4 The settlement as a place

- Named residents with a profession, home and workplace, shown as villagers with a daily routine (sleep,
  work, market, home), drawn from the settlement's population when a player is near. Reuse the colony
  citizen's schedule and state machine, driven by the settlement's books rather than its own needs.
- Larger buildings from the existing blueprint system: houses with beds and doors, a warehouse whose
  containers are registered NPC storage, workplaces per industry, walls and gates, docks.
- Roads between trading partners (surface paths, laid a stretch at a time when chunks are loaded).
- Visible caravans: a trader and pack animals on the road when a player is near the route, carrying the
  shipment's actual cargo. Interception and escort become possible.
- Outposts founded visibly (settlers leave, a site is staked, buildings rise over days).

### 2.5 Economy, deeper

- Trade agreements between a player (or a player colony) and a settlement: standing orders at an agreed rate.
- Player colonies as trading partners: caravans between a colony's Trading Post and NPC settlements, through
  the journal.
- Investment: a player funds a building in a settlement with goods and receives a share of its output.
- Buy and sell orders left with a merchant.
- Seasonal production (harvests, winter) and an economic history per settlement (`/civ economy history`).

### 2.6 Diplomacy with players

- Embassies: a player or colony can propose treaties, mediate, pay or demand tribute, and negotiate peace.
- Diplomatic missions as contracts (carry a message, escort an envoy).
- Territory: borders drawn from settlements, disputes where they overlap.
- Player rulers: high standing opens office, and an elective government can choose a player.

### 2.7 Crime

Only explicitly registered NPC storage, livestock and caravans can ever be targeted; player property stays
behind the existing protection. Planned: warehouse theft, pickpocketing, livestock theft, caravan robbery,
smuggling past embargoes, sabotage, bribery, spying; witnesses, suspicion, evidence, investigations, wanted
status, bounties, fines and prison terms, all through the journal so stolen goods are accounted for.

### 2.8 Warfare in the world

- Armies as groups of soldier bodies when a player is near, abstract otherwise (the existing colony military
  already does both; reuse it).
- Sieges that damage only the besieged settlement's own registered structures, and repair afterwards.
- Players as mercenaries, suppliers, saboteurs and peace-brokers, with contracts and reputation at stake.
- NPC civilisations and player colonies at war with each other, under the existing shield and war rules.

### 2.9 Events, missions and the past

- The rest of the event list (refugee columns, expeditions, prison breaks, diplomatic visits, trade route
  disruption, major construction).
- An adventurers' guild with escort, hunt, rescue, exploration, bounty and defence missions; every reward an
  idempotent journal entry.
- Prisoners of war, ransom, exchange and escape for NPC civilisations (the colony prison system exists).
- Fallen civilisations from before the world began: generated ruins, forgotten mines, libraries with written
  histories, buried caches (one bounded reward each, placed with the structure like vanilla loot).

### 3.0 Integration

Balance passes with real player data, performance profiling on a busy server, a security review of every
journal consumer, complete documentation, and compatibility testing across upgrades from 2.2.

## 5. How to work on it

- Keep `civ`, `core` and the pure part of `txn` free of server classes; put new rules there and test them.
- Anything that gives or takes a player's items goes through `TransactionManager`, with an idempotency key if
  it can be asked for twice.
- Anything that adds items to the world must be an industry or come out of an existing store.
- A new table means a new numbered migration, never an edit to an old one.
- `./gradlew build` runs every test. Do not describe a feature as tested on a live server unless it was.
