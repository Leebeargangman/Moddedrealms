// Fake AI replies for `--mock` runs: lets you test the bots, the event loop and the logging for free.
// It follows the same progression a real bot would, very roughly, based on the state text.

export function mockReply(kind, user) {
  if (kind === "memory") return "I am new here. I gathered some wood and looked around near the village.";
  // Only look at the inventory line, not at blocks the bot can see nearby.
  const inv = (user.match(/inventory: (.*)/) || [])[1] || "";
  const has = (item) => new RegExp(`\\b${item}\\b`).test(inv);
  if (/hp ([0-7])\//.test(user)) return JSON.stringify({ action: "flee" });
  if (/food ([0-9]|1[0-3])\//.test(user) && /(bread|beef|porkchop|apple|chicken|mutton|potato|carrot)/.test(inv)) return JSON.stringify({ action: "eat" });
  if (/Recent events:[\s\S]*(chat from|says)/.test(user) && Math.random() < 0.5) return JSON.stringify({ action: "chat", say: "Hello! I'm busy gathering, want to help?" });
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
