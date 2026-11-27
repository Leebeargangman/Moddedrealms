package com.colonysmp.gui;

import com.colonysmp.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Asks a player to type an answer in chat (colony names). The answer is handled on the main thread. */
public final class ChatPrompt implements Listener {

    private record Pending(Consumer<String> then, long until) {}

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String question, int seconds, Consumer<String> then) {
        pending.put(p.getUniqueId(), new Pending(then, System.currentTimeMillis() + seconds * 1000L));
        Text.send(p, question);
        Text.raw(p, "<dark_gray>  (type it in chat within " + seconds + "s, or type <white>cancel</white>)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.remove(e.getPlayer().getUniqueId());
        if (pd == null) return;
        if (System.currentTimeMillis() > pd.until) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (msg.equalsIgnoreCase("cancel")) {
                Text.send(p, "<gray>Cancelled.");
                return;
            }
            pd.then.accept(msg);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
