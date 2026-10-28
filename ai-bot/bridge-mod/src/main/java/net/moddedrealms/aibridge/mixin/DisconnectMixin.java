package net.moddedrealms.aibridge.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import net.moddedrealms.aibridge.AiBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers why the server disconnected us (kick message, queue full, ...) for the disconnect event. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class DisconnectMixin {
	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void aibridge$rememberReason(DisconnectionDetails details, CallbackInfo ci) {
		AiBridge.lastDisconnectReason = details.reason().getString();
	}
}
