# Moddedrealms Network (Velocity)

A small Velocity plugin with network-wide shortcut commands. It ships one: **`/minigames`** (alias `/minigame`)
sends the player to the `minigames` server from wherever they are. If they're already there it says so.

More shortcuts go in `plugins/moddedrealms-network/shortcuts.properties` (read at proxy start):

```
# command = server,label[,alias,alias...]   (server = a name from velocity.toml [servers])
minigames=minigames,the minigames,minigame
```

Built against velocity-api 4.1.0; runs on the network's Velocity 4.3.1. Build with `mvn package` (JDK 21+); the jar is
`target/ModdedrealmsNetwork-1.0.0.jar`.
