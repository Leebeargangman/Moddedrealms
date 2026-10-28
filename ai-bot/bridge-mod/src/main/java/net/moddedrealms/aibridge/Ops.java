package net.moddedrealms.aibridge;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IBaritoneProcess;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Everything the manager can ask for. Always runs on the client thread. */
final class Ops {
	private Ops() {
	}

	static JsonElement handle(String op, JsonObject a) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		switch (op) {
			case "ping":
				return new JsonPrimitive("pong");
			case "state":
				return state(mc);
			case "connect": {
				String host = str(a, "host");
				if (mc.level != null) mc.disconnectFromWorld(Component.literal("Switching server"));
				AiBridge.awaitingJoin = true;
				if (a.has("version")) setProtocolVersion(str(a, "version"));
				ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(host), new ServerData("AI bot", host, ServerData.Type.OTHER), false, null);
				return JsonNull.INSTANCE;
			}
			case "disconnect":
				if (mc.level != null) mc.disconnectFromWorld(Component.literal(a.has("reason") ? str(a, "reason") : "Disconnected"));
				return JsonNull.INSTANCE;
			case "quit":
				mc.stop();
				return JsonNull.INSTANCE;
		}

		if (p == null || mc.gameMode == null || mc.getConnection() == null) throw new IllegalStateException("not in a world");
		IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		switch (op) {
			case "chat": {
				String text = str(a, "text");
				if (text.startsWith("/")) mc.getConnection().sendCommand(text.substring(1));
				else mc.getConnection().sendChat(text);
				return JsonNull.INSTANCE;
			}
			case "baritone":
				return new JsonPrimitive(baritone.getCommandManager().execute(str(a, "command")));
			case "build": {
				File file = new File(mc.gameDirectory, "schematics/" + new File(str(a, "file")).getName());
				Vec3i origin = new Vec3i(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
				return new JsonPrimitive(baritone.getBuilderProcess().build(file.getName(), file, origin));
			}
			case "stop":
				baritone.getPathingBehavior().cancelEverything();
				return JsonNull.INSTANCE;
			case "respawn":
				p.respawn();
				return JsonNull.INSTANCE;
			case "attack": {
				Entity e = mc.level.getEntity(a.get("entity").getAsInt());
				if (e == null) throw new IllegalArgumentException("no such entity");
				Aim.attack(e.getId());
				return JsonNull.INSTANCE;
			}
			case "useItem":
				return new JsonPrimitive(mc.gameMode.useItem(p, hand(a)).toString());
			case "releaseUse":
				mc.gameMode.releaseUsingItem(p);
				return JsonNull.INSTANCE;
			case "lookAt":
				p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new Vec3(a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble()));
				return JsonNull.INSTANCE;
			case "key": {
				// Hold or release a control, e.g. "use" while eating (the game cancels eating when use isn't held).
				var o = mc.options;
				var key = switch (str(a, "key")) {
					case "use" -> o.keyUse;
					case "attack" -> o.keyAttack;
					case "jump" -> o.keyJump;
					case "sneak" -> o.keyShift;
					case "sprint" -> o.keySprint;
					case "forward" -> o.keyUp;
					case "back" -> o.keyDown;
					case "left" -> o.keyLeft;
					case "right" -> o.keyRight;
					default -> throw new IllegalArgumentException("unknown key " + str(a, "key"));
				};
				key.setDown(a.get("down").getAsBoolean());
				return JsonNull.INSTANCE;
			}
			case "select":
				p.getInventory().setSelectedSlot(a.get("slot").getAsInt());
				return JsonNull.INSTANCE;
			case "swapHands":
				p.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
				return JsonNull.INSTANCE;
			case "click": {
				AbstractContainerMenu menu = p.containerMenu;
				mc.gameMode.handleContainerInput(menu.containerId, a.get("slot").getAsInt(), a.has("button") ? a.get("button").getAsInt() : 0,
					ContainerInput.valueOf(a.has("mode") ? str(a, "mode") : "PICKUP"), p);
				return container(p);
			}
			case "closeContainer":
				p.closeContainer();
				return JsonNull.INSTANCE;
			case "container":
				return container(p);
			case "placeRecipe":
				return placeRecipe(mc, p, item(str(a, "item")));
			case "creativeGive": {
				ItemStack stack = new ItemStack(item(str(a, "item")), a.has("count") ? a.get("count").getAsInt() : 64);
				int slot = a.has("slot") ? a.get("slot").getAsInt() : p.getInventory().getSelectedSlot();
				mc.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
				return JsonNull.INSTANCE;
			}
			case "blockAt":
				return block(mc.level.getBlockState(pos(a)));
			case "findBlocks":
				return findBlocks(mc, p, a);
			case "portalSite":
				return portalSite(mc, p, a.has("radius") ? a.get("radius").getAsInt() : 10);
			case "groundBelow": {
				// For each {x,z}: y of the first solid block below fromY within depth, or null (air/unloaded).
				int fromY = a.get("fromY").getAsInt();
				int depth = a.has("depth") ? a.get("depth").getAsInt() : 32;
				JsonArray out = new JsonArray();
				for (JsonElement e : a.getAsJsonArray("points")) {
					JsonObject pt = e.getAsJsonObject();
					int x = pt.get("x").getAsInt();
					int z = pt.get("z").getAsInt();
					JsonElement found = JsonNull.INSTANCE;
					for (int y = fromY; y > fromY - depth; y--) {
						BlockPos bp = new BlockPos(x, y, z);
						if (!mc.level.isLoaded(bp)) break;
						BlockState st = mc.level.getBlockState(bp);
						if (st.isSolid() || !st.getFluidState().isEmpty()) {
							found = new JsonPrimitive(y);
							break;
						}
					}
					out.add(found);
				}
				return out;
			}
			default:
				throw new IllegalArgumentException("unknown op " + op);
		}
	}

	// ------------------------------------------------------------------ state

	static JsonObject state(Minecraft mc) {
		JsonObject s = new JsonObject();
		LocalPlayer p = mc.player;
		s.addProperty("inWorld", p != null && mc.level != null);
		s.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getSimpleName());
		if (mc.getCurrentServer() != null) s.addProperty("server", mc.getCurrentServer().ip);
		if (p == null || mc.level == null) return s;

		JsonObject me = new JsonObject();
		me.addProperty("name", p.getGameProfile().name());
		me.addProperty("x", p.getX());
		me.addProperty("y", p.getY());
		me.addProperty("z", p.getZ());
		me.addProperty("yaw", p.getYRot());
		me.addProperty("pitch", p.getXRot());
		me.addProperty("dimension", mc.level.dimension().identifier().getPath());
		me.addProperty("health", p.getHealth());
		me.addProperty("food", p.getFoodData().getFoodLevel());
		me.addProperty("saturation", p.getFoodData().getSaturationLevel());
		me.addProperty("air", p.getAirSupply());
		me.addProperty("onGround", p.onGround());
		me.addProperty("inWater", p.isInWater());
		me.addProperty("inLava", p.isInLava());
		me.addProperty("onFire", p.isOnFire());
		me.addProperty("dead", p.isDeadOrDying());
		me.addProperty("gameMode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName());
		me.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
		me.addProperty("isUsingItem", p.isUsingItem());
		// Ops can dig at spawn and run admin commands.
		me.addProperty("op", p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER));
		s.add("player", me);

		JsonArray inv = new JsonArray();
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty()) continue;
			JsonObject o = stack(st);
			o.addProperty("slot", i);
			inv.add(o);
		}
		s.add("inventory", inv);
		JsonObject eq = new JsonObject();
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
			ItemStack st = p.getItemBySlot(slot);
			eq.add(slot.getName(), st.isEmpty() ? JsonNull.INSTANCE : stack(st));
		}
		s.add("equipment", eq);

		JsonArray ents = new JsonArray();
		List<Entity> near = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) if (e != p && e.distanceTo(p) < 48) near.add(e);
		near.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
		for (Entity e : near.subList(0, Math.min(near.size(), 40))) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.getId());
			o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
			if (e instanceof Player pl) o.addProperty("name", pl.getGameProfile().name());
			o.addProperty("x", e.getX());
			o.addProperty("y", e.getY());
			o.addProperty("z", e.getZ());
			o.addProperty("dist", Math.round(e.distanceTo(p) * 10) / 10.0);
			if (e instanceof LivingEntity le) {
				o.addProperty("health", le.getHealth());
				ItemStack held = le.getMainHandItem();
				if (!held.isEmpty()) o.addProperty("held", BuiltInRegistries.ITEM.getKey(held.getItem()).getPath());
				JsonArray armor = new JsonArray();
				for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
					ItemStack st = le.getItemBySlot(slot);
					if (!st.isEmpty()) armor.add(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
				}
				if (!armor.isEmpty()) o.add("armor", armor);
			}
			ents.add(o);
		}
		s.add("entities", ents);

		JsonArray online = new JsonArray();
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			if (online.size() >= 200) break;
			online.add(info.getProfile().name());
		}
		s.add("tabList", online);

		s.addProperty("time", mc.level.getOverworldClockTime() % 24000);
		s.addProperty("raining", mc.level.isRaining());
		// Servers protect the area around world spawn; the agent walks out before it digs or builds.
		BlockPos spawn = mc.level.getLevelData().getRespawnData().pos();
		JsonObject ws = new JsonObject();
		ws.addProperty("x", spawn.getX());
		ws.addProperty("y", spawn.getY());
		ws.addProperty("z", spawn.getZ());
		s.add("worldSpawn", ws);
		s.add("baritone", baritoneState());
		if (mc.screen instanceof AbstractContainerScreen<?>) s.add("container", container(p));
		return s;
	}

	private static JsonObject baritoneState() {
		JsonObject b = new JsonObject();
		IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		b.addProperty("pathing", baritone.getPathingBehavior().isPathing());
		// Still working out a path (long trips in the nether take seconds): not idle yet.
		b.addProperty("calculating", baritone.getPathingBehavior().getInProgress().isPresent());
		if (baritone.getPathingBehavior().getGoal() != null) b.addProperty("goal", baritone.getPathingBehavior().getGoal().toString());
		baritone.getPathingControlManager().mostRecentInControl().ifPresent((IBaritoneProcess pr) -> {
			b.addProperty("process", pr.displayName());
			b.addProperty("processActive", pr.isActive());
		});
		baritone.getPathingBehavior().estimatedTicksToGoal().ifPresent(t -> b.addProperty("etaTicks", Math.round(t)));
		b.addProperty("building", baritone.getBuilderProcess().isActive());
		b.addProperty("buildPaused", baritone.getBuilderProcess().isPaused());
		b.addProperty("mining", baritone.getMineProcess().isActive());
		b.addProperty("following", baritone.getFollowProcess().isActive());
		return b;
	}

	private static JsonObject stack(ItemStack st) {
		JsonObject o = new JsonObject();
		o.addProperty("item", BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
		o.addProperty("count", st.getCount());
		if (st.isEnchanted()) o.addProperty("enchanted", true);
		if (st.isDamageableItem()) {
			o.addProperty("damage", st.getDamageValue());
			o.addProperty("maxDamage", st.getMaxDamage());
		}
		return o;
	}

	private static JsonObject container(LocalPlayer p) {
		AbstractContainerMenu menu = p.containerMenu;
		JsonObject c = new JsonObject();
		c.addProperty("id", menu.containerId);
		c.addProperty("type", menu.getClass().getSimpleName());
		JsonArray slots = new JsonArray();
		for (Slot slot : menu.slots) {
			if (!slot.hasItem()) continue;
			JsonObject o = stack(slot.getItem());
			o.addProperty("slot", slot.index);
			o.addProperty("playerInv", slot.container == p.getInventory());
			slots.add(o);
		}
		c.add("slots", slots);
		c.addProperty("size", menu.slots.size());
		return c;
	}

	// ------------------------------------------------------------------ crafting

	/**
	 * Ask the server to fill the open crafting grid (crafting table, or the 2x2 inventory grid) with a
	 * known recipe for the item that fits the grid and can be made from the inventory. The server fills
	 * the grid a moment later; the caller then takes the result from slot 0.
	 */
	private static JsonObject placeRecipe(Minecraft mc, LocalPlayer p, Item wanted) {
		AbstractCraftingMenu menu = p.containerMenu instanceof AbstractCraftingMenu m ? m : p.inventoryMenu;
		int gw = menu.getGridWidth();
		int gh = menu.getGridHeight();
		var ctx = SlotDisplayContext.fromLevel(mc.level);
		StackedItemContents contents = new StackedItemContents();
		p.getInventory().fillStackedContents(contents);
		boolean tooBig = false;
		for (RecipeCollection col : p.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : col.getRecipes()) {
				if (entry.resultItems(ctx).stream().noneMatch(r -> r.is(wanted))) continue;
				if (!entry.canCraft(contents)) continue;
				boolean fits = switch (entry.display()) {
					case ShapedCraftingRecipeDisplay sh -> sh.width() <= gw && sh.height() <= gh;
					case ShapelessCraftingRecipeDisplay sl -> sl.ingredients().size() <= gw * gh;
					default -> false;
				};
				if (!fits) {
					tooBig = true;
					continue;
				}
				mc.gameMode.handlePlaceRecipe(menu.containerId, entry.id(), false);
				JsonObject o = new JsonObject();
				o.addProperty("placed", true);
				o.addProperty("containerId", menu.containerId);
				return o;
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("placed", false);
		o.addProperty("needsTable", tooBig);
		return o;
	}

	// ------------------------------------------------------------------ blocks

	private static JsonObject block(BlockState state) {
		JsonObject o = new JsonObject();
		o.addProperty("name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
		o.addProperty("solid", state.isSolid());
		o.addProperty("air", state.isAir());
		return o;
	}

	/** Nearest blocks of the given names within a cube (radius capped at 32). */
	/**
	 * Nearest spot for a 4x5 portal frame: the whole frame area clear, solid ground under it, and room in front to
	 * stand while building. Building right where we stand fails in caves and on slopes. Returns the frame's lower
	 * corner and the axis it runs along (x or z), or null.
	 */
	private static JsonElement portalSite(Minecraft mc, LocalPlayer p, int r) {
		BlockPos c = p.blockPosition();
		BlockPos best = null;
		Direction.Axis bestAxis = null;
		double bestD = Double.MAX_VALUE;
		for (int dy = -3; dy <= 3; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos o = c.offset(dx, dy, dz);
					double d = o.distSqr(c);
					if (d >= bestD) continue;
					for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
						if (portalFits(mc, o, axis)) {
							best = o;
							bestAxis = axis;
							bestD = d;
							break;
						}
					}
				}
			}
		}
		if (best == null) return JsonNull.INSTANCE;
		JsonObject o = new JsonObject();
		o.addProperty("x", best.getX());
		o.addProperty("y", best.getY());
		o.addProperty("z", best.getZ());
		o.addProperty("axis", bestAxis == Direction.Axis.X ? "x" : "z");
		return o;
	}

	private static boolean portalFits(Minecraft mc, BlockPos o, Direction.Axis axis) {
		Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		Direction front = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
		for (int i = 0; i < 4; i++) {
			BlockPos col = o.relative(along, i);
			BlockPos below = col.below();
			if (!mc.level.isLoaded(below) || !mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) return false;
			for (int j = 0; j < 5; j++) if (!clear(mc, col.above(j))) return false;
			// Room to stand in front while placing.
			BlockPos f = col.relative(front);
			if (!clear(mc, f) || !clear(mc, f.above())) return false;
		}
		BlockPos stand = o.relative(along, 1).relative(front).below();
		return mc.level.getBlockState(stand).isFaceSturdy(mc.level, stand, Direction.UP);
	}

	private static boolean clear(Minecraft mc, BlockPos pos) {
		BlockState st = mc.level.getBlockState(pos);
		return st.getFluidState().isEmpty() && (st.isAir() || st.canBeReplaced());
	}

	private static JsonArray findBlocks(Minecraft mc, LocalPlayer p, JsonObject a) {
		Set<String> names = new HashSet<>();
		for (JsonElement e : a.getAsJsonArray("names")) names.add(e.getAsString());
		int r = Math.min(32, a.has("radius") ? a.get("radius").getAsInt() : 16);
		int max = a.has("max") ? a.get("max").getAsInt() : 20;
		BlockPos c = p.blockPosition();
		List<BlockPos> hits = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					if (!mc.level.isLoaded(m)) continue;
					String name = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(m).getBlock()).getPath();
					if (names.contains(name)) hits.add(m.immutable());
				}
			}
		}
		hits.sort(Comparator.comparingDouble(b -> b.distSqr(c)));
		JsonArray out = new JsonArray();
		for (BlockPos b : hits.subList(0, Math.min(max, hits.size()))) {
			JsonObject o = new JsonObject();
			o.addProperty("x", b.getX());
			o.addProperty("y", b.getY());
			o.addProperty("z", b.getZ());
			o.addProperty("name", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b).getBlock()).getPath());
			out.add(o);
		}
		return out;
	}

	// ------------------------------------------------------------------ args

	/** ViaFabricPlus's target version, set by reflection so the mod also works without ViaFabricPlus. */
	private static void setProtocolVersion(String name) {
		try {
			Class<?> pv = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
			Object version = pv.getMethod("getClosest", String.class).invoke(null, name);
			if (version == null) throw new IllegalArgumentException("unknown version " + name);
			Object impl = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus").getMethod("getImpl").invoke(null);
			impl.getClass().getMethod("setTargetVersion", pv).invoke(impl, version);
		} catch (ClassNotFoundException e) {
			// no ViaFabricPlus: only the client's own version works
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("can't select version " + name + ": " + e);
		}
	}

	private static String str(JsonObject a, String k) {
		if (!a.has(k)) throw new IllegalArgumentException("missing " + k);
		return a.get(k).getAsString();
	}

	static BlockPos pos(JsonObject a) {
		return new BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
	}

	static InteractionHand hand(JsonObject a) {
		return a.has("hand") && "off".equals(a.get("hand").getAsString()) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
	}

	private static Item item(String name) {
		Identifier id = Identifier.parse(name.contains(":") ? name : "minecraft:" + name);
		return BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new IllegalArgumentException("unknown item " + name));
	}
}
