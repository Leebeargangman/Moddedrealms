// Minecraft accounts the bots can use: Microsoft (tokens stay on this machine) or offline names.
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import prismarineAuth from 'prismarine-auth'
import { ROOT } from '../config.js'
import { offlineUuid } from '../client/launcher.js'

const { Authflow, Titles } = prismarineAuth
const DIR = path.join(ROOT, 'accounts')
const NAME_RE = /^\w{3,16}$/

// Settings that worked where the default Nintendo Switch app id was refused.
const FLOW = { authTitle: Titles.MinecraftJava, deviceType: 'Win32', flow: 'sisu' }

fs.mkdirSync(DIR, { recursive: true })
const metaFile = id => path.join(DIR, `${id}.json`)
const cacheDir = id => path.join(DIR, `${id}-auth`)

export function listAccounts () {
  return fs.readdirSync(DIR).filter(f => f.endsWith('.json')).map(f => JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8')))
}

export function getAccount (id) {
  try { return JSON.parse(fs.readFileSync(metaFile(id), 'utf8')) } catch { return null }
}

function save (acc) {
  fs.writeFileSync(metaFile(acc.id), JSON.stringify(acc, null, 2))
  return acc
}

export function addOffline (name, addedBy) {
  if (!NAME_RE.test(name)) throw new Error('offline names are 3-16 letters, numbers or _')
  return save({ id: `off-${name.toLowerCase()}`, type: 'offline', name, uuid: offlineUuid(name), addedBy, added: new Date().toISOString() })
}

/**
 * Start linking a Microsoft account. `onCode({ userCode, verificationUri })` fires as soon as Microsoft
 * hands out the code; the promise resolves with the account once the person has signed in.
 */
export async function linkMicrosoft (addedBy, onCode) {
  const id = `ms-${crypto.randomBytes(4).toString('hex')}`
  fs.mkdirSync(cacheDir(id), { recursive: true, mode: 0o700 })
  const flow = new Authflow(id, cacheDir(id), FLOW, code => onCode({ userCode: code.user_code, verificationUri: code.verification_uri }))
  try {
    const { profile } = await flow.getMinecraftJavaToken({ fetchProfile: true })
    if (!profile?.name) throw new Error('this Microsoft account has no Minecraft Java profile')
    // One entry per Minecraft account: re-linking replaces the old one.
    for (const old of listAccounts()) if (old.type === 'microsoft' && old.uuid === profile.id) removeAccount(old.id)
    return save({ id, type: 'microsoft', name: profile.name, uuid: profile.id, addedBy, added: new Date().toISOString() })
  } catch (e) {
    fs.rmSync(cacheDir(id), { recursive: true, force: true })
    throw new Error(`Microsoft sign-in failed: ${String(e.message).split('\n')[0]}${/invalid_grant/.test(e.message) ? ' (turn on two-step verification for the account and try again)' : ''}`)
  }
}

export function removeAccount (id) {
  fs.rmSync(metaFile(id), { force: true })
  fs.rmSync(cacheDir(id), { recursive: true, force: true })
}

/** Credentials to launch the client with: { name, uuid, accessToken } or { name, offline: true }. */
export async function session (id) {
  const acc = getAccount(id)
  if (!acc) throw new Error(`unknown account ${id}`)
  if (acc.type === 'offline') return { name: acc.name, uuid: acc.uuid, offline: true }
  const flow = new Authflow(id, cacheDir(id), FLOW, () => {
    throw new Error(`the Microsoft sign-in for ${acc.name} expired: link the account again in the Bot tab`)
  })
  const { token, profile } = await flow.getMinecraftJavaToken({ fetchProfile: true })
  return { name: profile.name, uuid: profile.id, accessToken: token, at: Date.now() }
}
