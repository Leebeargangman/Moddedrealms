// Mineflayer sends positions but, on 1.21.2+, never the packets a real client sends every tick:
// player_input (which movement keys are held) and tick_end. Vanilla servers don't care, but
// anticheats that simulate movement from the reported keys (e.g. Grim) see "moved without
// pressing anything" and teleport the bot back every tick. This plugin fills them in.
export function inputsPlugin (bot) {
  const types = bot.registry.protocol?.play?.toServer?.types ?? {}
  const hasInput = !!types.packet_player_input
  const hasTickEnd = !!types.packet_tick_end
  if (!hasInput && !hasTickEnd) return

  // Mineflayer eases the reported view toward the target over several ticks. Moving while the
  // reported yaw still lags behind looks impossible to a movement-simulating anticheat.
  if (bot.physics) { bot.physics.yawSpeed = 1e6; bot.physics.pitchSpeed = 1e6 }

  let last = ''
  let ticked = false
  bot.on('physicsTick', () => {
    // physicsTick runs before this tick's movement packet, so closing the previous tick here
    // yields the vanilla order: input, movement, tick_end.
    if (hasTickEnd && ticked) bot._client.write('tick_end', {})
    ticked = true
    if (!hasInput) return
    const c = bot.controlState
    const inputs = {
      forward: c.forward, backward: c.back, left: c.left, right: c.right, jump: c.jump, shift: c.sneak, sprint: c.sprint
    }
    const key = JSON.stringify(inputs)
    if (key === last) return
    last = key
    bot._client.write('player_input', { inputs })
  })
  bot.on('respawn', () => { last = ''; ticked = false })

  // 1.21.3+ movement packets carry a "bumped into a wall" flag that mineflayer leaves unset.
  const write = bot._client.write.bind(bot._client)
  bot._client.write = (name, params) => {
    if (params?.flags && typeof params.flags === 'object' && /^(position|position_look|look|flying)$/.test(name)) {
      params.flags.hasHorizontalCollision = !!bot.entity?.isCollidedHorizontally
    }
    return write(name, params)
  }
}
