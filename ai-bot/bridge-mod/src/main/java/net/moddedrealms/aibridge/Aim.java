package net.moddedrealms.aibridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hits entities and right-clicks blocks the way a player does: turn towards the target over a few ticks, and click
 * only once the crosshair is on it. Snapping the view and clicking in the same instant sends the click before the
 * server sees the turn: anti-cheats (Vulcan, Grim...) kick for it on entities (KillAura) and silently cancel it on
 * blocks (chests and crafting tables don't open, blocks aren't placed).
 */
final class Aim {
	private static final double REACH = 3.0;

	private static int target = -1;
	private static int ticksLeft;

	private record BlockUse(BlockPos pos, Direction face, InteractionHand hand, CompletableFuture<JsonElement> done) {}
	private static BlockUse use;
	private static int useTicks;

	private Aim() {}

	/** Queue a hit; it happens within the next ~half second if the entity can be aimed at, else it's dropped. */
	static void attack(int entityId) {
		target = entityId;
		ticksLeft = 10;
	}

	/** Right-click a block face once it's in the crosshair; completes with the interaction result. */
	static CompletableFuture<JsonElement> useOn(JsonObject a) {
		CompletableFuture<JsonElement> done = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				Direction face = a.has("face") ? Direction.byName(a.get("face").getAsString()) : Direction.UP;
				if (use != null) use.done.completeExceptionally(new IllegalStateException("replaced by another click"));
				use = new BlockUse(Ops.pos(a), face == null ? Direction.UP : face, Ops.hand(a), done);
				useTicks = 0;
			} catch (RuntimeException e) {
				done.completeExceptionally(e);
			}
		});
		return done;
	}

	static void tick(Minecraft mc) {
		tickBlock(mc);
		tickEntity(mc);
	}

	private static void tickBlock(Minecraft mc) {
		if (use == null) return;
		BlockUse u = use;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) {
			use = null;
			u.done.completeExceptionally(new IllegalStateException("not in a world"));
			return;
		}
		Vec3 eye = p.getEyePosition();
		Vec3 aimAt = Vec3.atCenterOf(u.pos).add(u.face.getStepX() * 0.5, u.face.getStepY() * 0.5, u.face.getStepZ() * 0.5);
		HitResult ray = mc.level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(4.5)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		boolean onTarget = ray instanceof BlockHitResult bh && ray.getType() == HitResult.Type.BLOCK && bh.getBlockPos().equals(u.pos) && bh.getDirection() == u.face;
		// A face hidden behind another block never lines up; after the turn, click it the old way rather than never.
		if (onTarget || ++useTicks > 12) {
			BlockHitResult hit = onTarget ? (BlockHitResult) ray : new BlockHitResult(aimAt, u.face, u.pos, false);
			use = null;
			String result = mc.gameMode.useItemOn(p, u.hand, hit).toString();
			p.swing(u.hand);
			u.done.complete(new JsonPrimitive(result));
			return;
		}
		turnTowards(p, aimAt);
	}

	private static void tickEntity(Minecraft mc) {
		if (target < 0) return;
		LocalPlayer p = mc.player;
		Entity e = p == null || mc.level == null ? null : mc.level.getEntity(target);
		if (e == null || !e.isAlive() || --ticksLeft < 0) {
			target = -1;
			return;
		}
		// Check before turning: this tick's movement packet already told the server where we look (END_CLIENT_TICK
		// runs after it), so the hit matches a view angle the server has seen.
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(REACH));
		if (e.getBoundingBox().clip(eye, end).isPresent() || e.getBoundingBox().contains(eye)) {
			mc.gameMode.attack(p, e);
			p.swing(InteractionHand.MAIN_HAND);
			target = -1;
			return;
		}
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		// Aim a little off the exact centre, like a person would.
		turnTowards(p, e.getBoundingBox().getCenter().add(rnd.nextGaussian() * 0.08, rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.08));
	}

	/** One tick of turning: at most ~30-50 degrees, so a big turn takes a few ticks like a mouse flick. */
	private static void turnTowards(LocalPlayer p, Vec3 at) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		Vec3 eye = p.getEyePosition();
		double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		float maxStep = 28f + rnd.nextFloat() * 22f;
		float dYaw = Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -maxStep, maxStep);
		float dPitch = Mth.clamp(pitch - p.getXRot(), -maxStep * 0.6f, maxStep * 0.6f);
		p.setYRot(p.getYRot() + dYaw);
		p.setXRot(Mth.clamp(p.getXRot() + dPitch, -90f, 90f));
	}
}
