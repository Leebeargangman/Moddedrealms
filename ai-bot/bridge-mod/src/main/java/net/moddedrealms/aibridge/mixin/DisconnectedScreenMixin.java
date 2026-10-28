package net.moddedrealms.aibridge.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.moddedrealms.aibridge.AiBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports why we were disconnected. Kicks while logging in (whitelist, ban, wrong version) never reach the
 * play-connection DISCONNECT event, and in-game kick messages can arrive after it; both end up on this screen.
 */
@Mixin(DisconnectedScreen.class)
public class DisconnectedScreenMixin {
	@Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
	private void aibridge$reportDisconnect(Screen parent, Component title, DisconnectionDetails details, Component buttonText, CallbackInfo ci) {
		AiBridge.disconnectScreen(details.reason().getString());
	}
}
