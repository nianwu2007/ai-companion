package com.aicompanion.mixin;

import com.aicompanion.engine.PlayerEngine;

import net.minecraft.client.input.Input;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 唯一的 mixin，而且非常小。
 *
 * 原版 Input#tick 每帧都拿键盘状态把 movementForward/movementSideways 重新写一遍，
 * 所以外部代码无论在哪一步赋值都会在下一帧被冲掉。
 * 我们在它的尾巴上接一手：只要 PlayerEngine 本 tick 有移动意图，就覆盖掉键盘结果。
 * 玩家一按键，引擎就让路（见 AiCompanionClient 的按键监听），不跟人抢方向盘。
 */
@Mixin(Input.class)
public abstract class InputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void aicompanion$applyEngineIntent(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        PlayerEngine.MoveIntent mi = PlayerEngine.currentIntent();
        if (mi == null) {
            return;
        }
        Input self = (Input) (Object) this;
        self.movementForward = mi.forward - 0.0f;
        self.movementSideways = mi.strafe;
        self.jumping = mi.jump;
        self.sneaking = mi.sneak;
    }
}
