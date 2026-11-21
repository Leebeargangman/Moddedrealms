// Fake AI replies for `--mock` runs: lets you test the bots, the event loop and the logging for free.
// It follows the same progression a real bot would, very roughly, based on the state text.

const lastLine = (user) => ((user.match(/Recent events[^\n]*\n([\s\S]*?)\nState:/) || [])[1] || "").trim().split("\n").pop() || "";

export function mockReply(kind, user) {
  if (kind === "memory") return "I am new here. I gathered some wood and looked around near the village.";
  // Only look at the inventory line, not at blocks the bot can see nearby.
  const inv = (user.match(/inventory: (.*)/) || [])[1] || "";
  const has = (item) => new RegExp(`\\b${item}\\b`).test(inv);
  if (/hp ([0-7])\//.test(user)) return JSON.stringify({ action: "flee" });
  // Phase 3: imposters kill when someone is alone with them; everyone votes, hides at night,
  // stores wood and builds huts when there is enough.
  const alone = (user.match(/Alone with you, nobody watching: ([^.\n]+)/) || [])[1];
  if (alone && alone !== "nobody right now" && Math.random() < 0.6) return JSON.stringify({ action: "kill", target: alone.split(", ")[0] });
  if (/you have not voted yet/.test(user)) {
    const names = [...user.matchAll(/villagers ([^·\n]+)/g)].flatMap((m) => m[1].split(", ").map((x) => x.split(" ")[0]));
    const me = (user.match(/^You are (\w+)/) || [])[1];
    return JSON.stringify({ action: "vote", target: names.length && Math.random() < 0.7 ? names[0] : me });
  }
  if (/Leader: YOU/.test(user) && /Laws: none yet/.test(user)) return JSON.stringify({ action: "law", target: "Nobody walks alone at night." });
  if (/· night/.test(user) && !/hide/.test(lastLine(user))) return JSON.stringify({ action: "hide" });
  const logs = Number((inv.match(/\w+_log (\d+)/) || [])[1] || 0);
  const chestLogs = Number((user.match(/Village chests[^\n]*?_log (\d+)/) || [])[1] || 0);
  if (chestLogs + logs >= 18 && Math.random() < 0.5) return JSON.stringify({ action: "build", target: "hut" });
  if (logs >= 10 && Math.random() < 0.5) return JSON.stringify({ action: "deposit", target: "wood" });
  if (/food ([0-9]|1[0-3])\//.test(user) && /(bread|beef|porkchop|apple|chicken|mutton|potato|carrot)/.test(inv)) return JSON.stringify({ action: "eat" });
  if (/\[new\]/.test(user) && /says: .*\[new\]/.test(user) && Math.random() < 0.5) return JSON.stringify({ action: "chat", say: "Hello! I'm busy gathering, want to help?" });
  // Sometimes take an open job from the task board, or post one.
  const open = [...user.matchAll(/- #(\d+) [^\n]*· open/g)].map((m) => m[1]);
  if (!/taken by you/.test(user) && open.length && Math.random() < 0.35) return JSON.stringify({ action: "claim", target: open[Math.floor(Math.random() * open.length)] });
  if (Math.random() < 0.05) return JSON.stringify({ action: "post", target: "gather oak_log", amount: 16 });
  const lastEvent = (user.match(/Recent events[^\n]*\n([\s\S]*?)\nState:/) || [])[1]?.trim().split("\n").pop() || "";
  if (/Task failed: craft/.test(lastEvent)) return JSON.stringify({ action: "gather", target: "wood", amount: 6 });
  if (!has("\\w+_log") && !has("\\w+_planks")) return JSON.stringify({ action: "gather", target: "wood", amount: 6 });
  if (!has("\\w+_planks")) return JSON.stringify({ action: "craft", target: "oak_planks", amount: 4 });
  if (!has("stick")) return JSON.stringify({ action: "craft", target: "stick", amount: 4 });
  if (!has("crafting_table") && !has("wooden_pickaxe")) return JSON.stringify({ action: "craft", target: "crafting_table" });
  if (!has("wooden_pickaxe") && !has("stone_pickaxe")) return JSON.stringify({ action: "craft", target: "wooden_pickaxe" });
  if (!has("cobblestone")) return JSON.stringify({ action: "gather", target: "stone", amount: 8 });
  return JSON.stringify({ action: Math.random() < 0.5 ? "explore" : "gather", target: "wood", amount: 8 });
}
