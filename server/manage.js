#!/usr/bin/env node
/*
 * Command-line tools for the website's accounts. Run from the project folder:
 *
 *   node server/manage.js admin add <username>        give a website account staff access
 *   node server/manage.js admin remove <username>     take it away again (rank-based access still applies)
 *   node server/manage.js password <username>         set a new password (you'll be asked for it)
 *   node server/manage.js users                       list accounts
 *   node server/manage.js plugin-key                  print a fresh random PLUGIN_API_KEY
 */
"use strict";

const crypto = require("node:crypto");
const readline = require("node:readline");

const [command, sub, arg] = process.argv.slice(2);

function usage() {
  console.log(`Usage:
  node server/manage.js admin add <username>
  node server/manage.js admin remove <username>
  node server/manage.js password <username>
  node server/manage.js users
  node server/manage.js plugin-key`);
  process.exit(1);
}

function ask(question) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    rl.question(question, (answer) => {
      rl.close();
      resolve(answer);
    });
  });
}

async function main() {
  if (command === "plugin-key") {
    console.log(crypto.randomBytes(24).toString("hex"));
    return;
  }

  const { db } = require("./db");
  const accounts = require("./accounts");

  function findUser(name) {
    const user = name && accounts.findUserByName(name);
    if (!user) {
      console.error(`No website account called "${name || ""}". Sign up on the website first.`);
      process.exit(1);
    }
    return user;
  }

  if (command === "admin" && (sub === "add" || sub === "remove") && arg) {
    const user = findUser(arg);
    db.prepare("UPDATE users SET role = ? WHERE id = ?").run(sub === "add" ? "admin" : null, user.id);
    console.log(sub === "add" ? `${user.username} now has staff access.` : `${user.username} no longer has the admin role.`);
    return;
  }

  if (command === "password" && sub) {
    const user = findUser(sub);
    const password = arg || (await ask(`New password for ${user.username}: `));
    const problem = accounts.passwordProblem(password);
    if (problem) {
      console.error(problem);
      process.exit(1);
    }
    db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(await accounts.hashPassword(password), user.id);
    accounts.endAllSessions(user.id);
    console.log(`Password updated for ${user.username}. They've been signed out everywhere.`);
    return;
  }

  if (command === "users") {
    const rows = db.prepare("SELECT * FROM users ORDER BY created_at").all();
    if (!rows.length) console.log("No accounts yet.");
    for (const u of rows) {
      const mc = u.mc_name ? `${u.mc_name} [${u.mc_rank || "no rank"}]` : "not linked";
      console.log(`${u.username.padEnd(24)} ${mc.padEnd(32)} ${accounts.isStaff(u) ? "STAFF" : ""}`);
    }
    return;
  }

  usage();
}

main().then(
  () => process.exit(0),
  (err) => {
    console.error(err.message);
    process.exit(1);
  }
);
