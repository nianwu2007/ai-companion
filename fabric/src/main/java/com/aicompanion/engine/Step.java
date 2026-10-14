package com.aicompanion.engine;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 手脚的最小动作集：转头、往前走、停。
 * 走动通过"移动意图"(PlayerEngine.MoveIntent) + 一个针对 Input#tick 的 mixin 生效，
 * 因为原版每 tick 都会用键盘状态把 movementForward 归零，不劫持就白写。
 */
public final class Step {

    private Step() {}

    /** 平滑转头看向某个点（每 tick 转一点，别瞬间 180° 甩头）。 */
    public static void look(ClientPlayerEntity p, Vec3d target) {
        Vec3d eyes = p.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));
        float yaw = p.getYaw() + MathHelper.wrapDegrees(wantYaw - p.getYaw()) * 0.45f;
        float pitch = p.getPitch() + (wantPitch - p.getPitch()) * 0.45f;
        p.setYaw(yaw);
        p.setPitch(MathHelper.clamp(pitch, -89f, 89f));
    }

    /** 朝目标走一步；sprint 只是让它姿态上更急一点。 */
    public static void walkTo(ClientPlayerEntity p, Vec3d target, boolean sprint) {
        look(p, target);
        boolean needJump = needsJump(p, target);
        p.setSprinting(sprint && !needJump);
        PlayerEngine.pushIntent(1.0f, 0.0f, needJump, false);
    }

    /** 停下（并且取消疾跑）。 */
    public static void stop(ClientPlayerEntity p) {
        p.setSprinting(false);
        PlayerEngine.pushIntent(0f, 0f, false, false);
    }

    private static boolean needsJump(ClientPlayerEntity p, Vec3d target) {
        if (target.y > p.getY() + 0.55) {
            return true;
        }
        if (!p.isOnGround()) {
            return false;
        }
        BlockPos ahead = BlockPos.ofFloored(
                p.getX() + (target.x - p.getX()) * 0.35,
                p.getY(),
                p.getZ() + (target.z - p.getZ()) * 0.35);
        return p.getWorld().getBlockState(ahead).blocksMovement()
                && !p.getWorld().getBlockState(ahead.up()).blocksMovement();
    }
}
