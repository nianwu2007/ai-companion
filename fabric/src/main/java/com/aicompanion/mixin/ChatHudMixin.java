package com.aicompanion.mixin;

import com.aicompanion.client.ChatWatcher;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 聊天栏每显示一行就瞄一眼（不改任何行为，纯旁听）。 */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("TAIL"))
    private void aicompanion$watch(Text message, CallbackInfo ci) {
        if (message != null) {
            ChatWatcher.onDisplayed(message.getString());
        }
    }
}
