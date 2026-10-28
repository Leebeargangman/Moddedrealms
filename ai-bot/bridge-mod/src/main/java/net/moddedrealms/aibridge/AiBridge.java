package net.moddedrealms.aibridge;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import baritone.api.BaritoneAPI;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. When started with -Daibridge.port and -Daibridge.token, opens a local socket the bot
 * manager uses to drive this client: Baritone commands, chat, inventory, and a live state feed.
 */
public class AiBridge implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("ai-bridge");
	/** Skip drawing the world: a bot has no one looking at the screen. */
	public static final boolean NO_RENDER = Boolean.getBoolean("aibridge.norender");
	public static volatile String lastDisconnectReason;
	/** True from a connect attempt (or start-up quick play) until we join a world or report why we couldn't. */
	public static volatile boolean awaitingJoin = true;
	static BridgeServer server;

	private int ticks;
	private boolean wasDead;

	@Override
	public void onInitializeClient() {
		int port = Integer.getInteger("aibridge.port", 0);
		String token = System.getProperty("aibridge.token", "");
		if (port == 0 || token.isEmpty()) {
			LOG.warn("aibridge.port / aibridge.token not set, bridge disabled");
			return;
		}
		server = new BridgeServer(port, token);
		server.start();

		ClientReceiveMessageEvents.GAME.register((message, overlay) ->
			server.event(event(overlay ? "actionbar" : "system", "text", message.getString())));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, time) ->
			server.event(event("chat", "text", message.getString(), "sender", sender == null ? null : sender.name())));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			lastDisconnectReason = null;
			awaitingJoin = false;
			server.event(event("join"));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			server.event(event("disconnect", "reason", lastDisconnectReason)));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);

		// Baritone talks only to the local chat ("Missing materials", "Unable to do it"...); pass that on too.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			var logger = BaritoneAPI.getSettings().logger;
			Consumer<Component> original = logger.value;
			logger.value = msg -> {
				original.accept(msg);
				server.event(event("baritone", "text", msg.getString()));
			};
		});
	}

	/** Called when the "Disconnected" screen opens; only reports failures before reaching a world. */
	public static void loginFailed(String reason) {
		if (!awaitingJoin || server == null) return;
		awaitingJoin = false;
		server.event(event("disconnect", "reason", reason));
	}

	private void tick(Minecraft mc) {
		if (!server.hasClient()) return;
		boolean dead = mc.player != null && mc.player.isDeadOrDying();
		if (dead && !wasDead) server.event(event("death"));
		wasDead = dead;
		// 4 state updates a second is plenty for the AI and its reflexes.
		if (++ticks % 5 == 0) {
			JsonObject msg = event("state");
			msg.add("state", Ops.state(mc));
			server.event(msg);
		}
	}

	static JsonObject event(String name, Object... kv) {
		JsonObject o = new JsonObject();
		o.addProperty("event", name);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			Object v = kv[i + 1];
			if (v == null) continue;
			if (v instanceof Number n) o.addProperty((String) kv[i], n);
			else if (v instanceof Boolean b) o.addProperty((String) kv[i], b);
			else o.addProperty((String) kv[i], v.toString());
		}
		return o;
	}
}
