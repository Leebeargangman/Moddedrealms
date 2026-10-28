package net.moddedrealms.aibridge.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.moddedrealms.aibridge.AiBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips drawing frames when running as a bot (-Daibridge.norender=true); the game still ticks normally. */
@Mixin(GameRenderer.class)
public class NoRenderMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void aibridge$skipRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		if (AiBridge.NO_RENDER) ci.cancel();
	}
}
