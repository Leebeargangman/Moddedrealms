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
 * Kicks while logging in (whitelist, ban, wrong version, connection refused) never reach the play-connection
 * DISCONNECT event; they only open this screen. Report them so the manager doesn't wait forever.
 */
@Mixin(DisconnectedScreen.class)
public class DisconnectedScreenMixin {
	@Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
	private void aibridge$reportLoginFailure(Screen parent, Component title, DisconnectionDetails details, Component buttonText, CallbackInfo ci) {
		AiBridge.loginFailed(details.reason().getString());
	}
}
