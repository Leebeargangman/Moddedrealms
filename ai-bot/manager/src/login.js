// One-off Microsoft sign-in. Caches tokens in the same place the bot uses, so the
// bot itself can start unattended afterwards.
import prismarineAuth from 'prismarine-auth'
import { loadConfig } from './config.js'

const { Authflow, Titles } = prismarineAuth

const cfg = loadConfig()
// Same options the bot passes to mineflayer (src/index.js), so they share the token cache.
const flow = new Authflow(cfg.account.username, cfg.account.profilesFolder, {
  authTitle: Titles[cfg.account.authTitle],
  deviceType: cfg.account.deviceType,
  flow: cfg.account.flow
}, code => {
  console.log(`\nOpen ${code.verification_uri} in a browser and enter the code: ${code.user_code}\n`)
})

const { profile } = await flow.getMinecraftJavaToken({ fetchProfile: true }).catch(e => {
  console.error(`Sign-in failed: ${e.message.split('\n')[0]}`)
  process.exit(1)
})
if (!profile?.name) {
  console.error('Signed in, but this Microsoft account has no Minecraft Java profile.')
  process.exit(1)
}
console.log(`Signed in as ${profile.name} (${profile.id}). Tokens cached in ${cfg.account.profilesFolder}`)
if (profile.name.toLowerCase() !== cfg.account.username.toLowerCase()) {
  console.warn(`Warning: expected ${cfg.account.username}. Check that you signed in with the right Microsoft account.`)
}
