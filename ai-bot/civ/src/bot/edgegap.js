// A small fix for Mineflayer's movement on Paper 1.21.11.
//
// When the bot walks along a wall, Mineflayer's physics leaves its hitbox EXACTLY touching the side of
// the block (for example its edge at z = 17.000000). Paper treats that as walking into the block and
// sends the bot back, every tick, so the bot freezes in place (seen on AITest201: ~20 setbacks a second,
// nothing in the server log). Keeping the hitbox 0.001 blocks off the block face stops it.
// Measured on a copy of AITest201's world: trap spots went from 1 of 5 reachable to 8 of 8.
//
// 'physicsTick' fires after Mineflayer moves the bot and before it sends the position to the server.

const HALF_WIDTH = 0.3; // a player's hitbox is 0.6 blocks wide
const EPS = 1e-6;       // "exactly on a block edge"
const GAP = 1e-3;       // how far to step back from it

export function installEdgeGap(bot) {
  bot.on("physicsTick", () => {
    const p = bot.entity?.position;
    if (!p) return;
    for (const axis of ["x", "z"]) {
      const min = p[axis] - HALF_WIDTH, max = p[axis] + HALF_WIDTH;
      if (Math.abs(min - Math.round(min)) < EPS) p[axis] += GAP;
      else if (Math.abs(max - Math.round(max)) < EPS) p[axis] -= GAP;
    }
  });
}
