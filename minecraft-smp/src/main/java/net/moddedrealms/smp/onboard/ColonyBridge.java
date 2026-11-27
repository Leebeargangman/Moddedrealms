package net.moddedrealms.smp.onboard;

import com.colonysmp.api.ColonyApi;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Calls into ColonySMP for onboarding. Every call is safe without ColonySMP, or with one older than 2.7 (which has
 * no starter camps or funnel): then it does nothing and says so.
 */
public final class ColonyBridge {
    private ColonyBridge() {
    }

    private static boolean present() {
        return Bukkit.getPluginManager().isPluginEnabled("ColonySMP");
    }

    public static boolean starterCamps() {
        if (!present()) return false;
        try {
            return ColonyApi.ready() && ColonyApi.starterCamps();
        } catch (LinkageError e) {
            return false;
        }
    }

    /** Null when the camp is on its way; otherwise why not. */
    public static String startColony(Player p, Consumer<String> done) {
        if (!present()) return "Colonies aren't running right now.";
        try {
            return ColonyApi.startColony(p, done);
        } catch (LinkageError e) {
            return "Colonies need an update for that. Use /colony create <name> for now.";
        }
    }

    public static void funnel(UUID player, String step) {
        if (!present()) return;
        try {
            ColonyApi.funnel(player, step);
        } catch (LinkageError ignored) {
            // an older ColonySMP has no funnel
        }
    }

    public static String funnelStep(UUID player) {
        if (!present()) return "";
        try {
            return ColonyApi.funnelStep(player);
        } catch (LinkageError e) {
            return "";
        }
    }
}
