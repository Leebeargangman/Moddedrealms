// pm2: runs the AI civilisation 24/7 in scheduled mode (a session every day, 17:30-20:30 UK time).
//   pm2 start ecosystem.config.cjs && pm2 save        (as the "bots" user on the VPS)
//   pm2 logs ai-civ        pm2 restart ai-civ        pm2 stop ai-civ
module.exports = {
  apps: [{
    name: "ai-civ",
    script: "src/index.js",
    args: "--scheduled",
    cwd: __dirname,
    interpreter: process.env.CIV_NODE || "/opt/node22/bin/node",
    node_args: "--no-warnings",
    kill_timeout: 20000,       // time to log the villagers off and write the report on stop/restart
    restart_delay: 10000,
    max_restarts: 20,
    time: true,                // timestamps in pm2 logs
  }],
};
