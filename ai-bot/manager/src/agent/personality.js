// Personalities for bots: who they are, what they like doing and what they'd rather not do. Shared by the AI
// bots (prompts, and free will over owner orders) and the crowd bots (simple rules). Each can be switched off
// without losing it.

// Activities, used to match likes and dislikes against actions and crowd orders.
export const ACTIVITIES = ['build', 'gather', 'mine', 'explore', 'fight', 'follow', 'stay', 'chat']

// What each AI action counts as.
const ACTION_ACTIVITY = {
  build: 'build', place_build: 'build',
  gather_wood: 'gather', hunt: 'fight', farm: 'gather',
  mine: 'mine', strip_mine: 'mine',
  travel_far: 'explore', explore: 'explore', goto: 'explore',
  fight: 'fight',
  follow_player: 'follow', come_to: 'follow',
  wait: 'stay', hide: 'stay', sleep: 'stay'
}

export const PRESETS = {
  builder: {
    text: 'A proud, practical builder. Always planning the next build, talks about materials and good spots, likes to show off finished work.',
    likes: ['build', 'gather'], dislikes: ['explore', 'fight']
  },
  explorer: {
    text: "A restless explorer. Always wants to see what's over the next hill, gets excited about new places and tells everyone about them.",
    likes: ['explore'], dislikes: ['stay', 'mine']
  },
  leader: {
    text: 'A bossy natural leader. Organises everyone, hands out jobs, wants the group to build a proper base together.',
    likes: ['build', 'chat'], dislikes: ['follow']
  },
  joker: {
    text: 'The class clown. Makes jokes and terrible puns, teases friends, never takes anything seriously.',
    likes: ['chat', 'explore'], dislikes: ['stay', 'mine']
  },
  nervous: {
    text: 'Nervous and cautious. Worries about mobs, the dark and heights, sticks close to friends and asks lots of questions.',
    likes: ['follow', 'stay'], dislikes: ['explore', 'fight', 'mine']
  },
  grumpy: {
    text: 'A grumpy old-timer. Complains about everything in short sentences, but secretly cares about friends and helps them.',
    likes: ['mine', 'stay'], dislikes: ['chat', 'follow']
  },
  friendly: {
    text: "Warm and chatty. Greets everyone by name, asks how they're doing, loves company and compliments other people's work.",
    likes: ['chat', 'follow', 'build'], dislikes: []
  }
}

const clip = (s, n) => String(s ?? '').replace(/\s+/g, ' ').trim().slice(0, n)
const tags = list => (Array.isArray(list) ? list : String(list ?? '').split(/[,;]/))
  .map(t => clip(t, 30).toLowerCase()).filter(Boolean).slice(0, 8)

export function randomPreset () {
  const names = Object.keys(PRESETS)
  return names[Math.floor(Math.random() * names.length)]
}

/**
 * A clean personality from what the Bots page or a command sent: a preset name, "random", free text, or an
 * object {enabled, preset, text, likes, dislikes, freeWill}. Null clears it.
 */
export function normalize (p) {
  if (p == null || p === '' || p === false) return null
  if (typeof p === 'string') p = { text: p }
  const asked = clip(p.preset ?? p.text, 300)
  const presetName = asked.toLowerCase() === 'random' ? randomPreset() : PRESETS[asked.toLowerCase()] ? asked.toLowerCase() : (p.preset && PRESETS[p.preset] ? p.preset : null)
  const preset = presetName ? PRESETS[presetName] : null
  const text = preset && (!p.text || p.text.toLowerCase() === presetName || p.text.toLowerCase() === 'random') ? preset.text : clip(p.text, 300)
  if (!text) return null
  return {
    enabled: p.enabled !== false,
    preset: presetName,
    text,
    likes: p.likes != null ? tags(p.likes) : preset?.likes ?? [],
    dislikes: p.dislikes != null ? tags(p.dislikes) : preset?.dislikes ?? [],
    freeWill: p.freeWill === true
  }
}

const active = p => p && p.enabled !== false && p.text

/** Lines for an AI prompt, or '' when the personality is off. */
export function promptText (p) {
  if (!active(p)) return ''
  const bits = [`YOUR PERSONALITY: ${p.text} Stay in character in what you choose and how you talk.`]
  if (p.likes?.length) bits.push(`You enjoy: ${p.likes.join(', ')}. Prefer these when you have a choice.`)
  if (p.dislikes?.length) bits.push(`You dislike: ${p.dislikes.join(', ')}. Avoid them when you can, and grumble when you can't.`)
  return bits.join('\n')
}

/** Short label for lists: the preset's name or the start of the text, and whether it's off. */
export function label (p) {
  if (!p) return null
  const name = p.preset ?? (p.text.length > 30 ? p.text.slice(0, 30) + '...' : p.text)
  return p.enabled === false ? `${name} (off)` : name
}

/** Does this personality dislike doing this action or activity? */
export function dislikes (p, actionOrActivity) {
  if (!active(p) || !p.dislikes?.length) return false
  const activity = ACTION_ACTIVITY[actionOrActivity] ?? actionOrActivity
  return p.dislikes.some(d => d === activity || d === actionOrActivity || activity.startsWith(d) || d.startsWith(activity))
}

export function likes (p, actionOrActivity) {
  if (!active(p) || !p.likes?.length) return false
  const activity = ACTION_ACTIVITY[actionOrActivity] ?? actionOrActivity
  return p.likes.some(l => l === activity || l === actionOrActivity)
}

/** Free will: may it say no to an order it dislikes? (Only when switched on, and never to "stop".) */
export function mayRefuse (p, actionOrActivity) {
  return !!(active(p) && p.freeWill && actionOrActivity !== 'stop' && dislikes(p, actionOrActivity))
}

// Short lines for bots without an AI model, by preset. {x} is the activity.
const LINES = {
  refuse: {
    builder: ["nah, I'd rather be building", 'not now, got plans to draw up', '{x}? waste of good building time'],
    explorer: ['boring! I want to see new places', 'nope, the horizon is calling'],
    leader: ["I'm the one giving orders here", "no, I've got a better plan"],
    joker: ["{x}? you're joking, right? that's my job", 'haha no'],
    nervous: ['n-no way, that sounds dangerous', "I don't want to, it's scary out there"],
    grumpy: ['no.', 'hmph. do it yourself', 'not a chance'],
    friendly: ["sorry, I'd really rather not do {x}", "can someone else do {x}? I'll help another way"],
    default: ["I'd rather not", 'no thanks']
  },
  grudging: {
    builder: ['fine, but then I build something', 'okay okay, {x} it is'],
    explorer: ['ugh, fine', 'alright, but only for a bit'],
    leader: ["fine, but I'm in charge next", 'okay, this once'],
    joker: ["fine, but I'm complaining the whole time", 'ok ok, no jokes for 5 minutes'],
    nervous: ['o-okay, if you say so...', "fine, but stay close"],
    grumpy: ['fine. happy now?', 'ugh. fine.'],
    friendly: ['okay, for you!', 'sure, if it helps'],
    default: ['fine', 'okay then']
  },
  happy: {
    builder: ['on it!', 'love it'], explorer: ['yes! adventure!', "let's go!"], leader: ['good call, follow me'],
    joker: ["let's gooo", 'say less'], nervous: ['okay, staying close', 'thank you'], grumpy: ['hm. fine.', 'finally something sensible'],
    friendly: ['yay, sure!', 'happy to!'], default: ['ok', 'on it']
  }
}

/** A canned line in character: kind is refuse | grudging | happy. */
export function line (p, kind, activity = 'that') {
  const set = LINES[kind] ?? LINES.happy
  const options = set[p?.preset] ?? set.default
  return options[Math.floor(Math.random() * options.length)].replace('{x}', activity)
}
