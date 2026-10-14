package com.aicompanion.mixin;

import com.aicompanion.client.ChatWatcher;

import net.minecraft.client.network.ClientPlayNetworkHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 你敲进聊天框的内容，在发出去之前截一份（单机时没有回显，得靠自己）。 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"))
    private void aicompanion$onSend(String content, CallbackInfo ci) {
        ChatWatcher.onPlayerSent(content);
    }
}
