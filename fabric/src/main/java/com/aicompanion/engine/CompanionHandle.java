package com.aicompanion.engine;

import com.aicompanion.entity.AICompanionEntity;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

/**
 * 同伴句柄（客户端）。
 *
 * 小怜现在是服务端实体，由 CompanionSpawner 在服务端生成、服务端 AI 驱动。
 * 客户端这边只负责"找到她"，不再自己造实体、也不再手动搬运。
 */
public final class CompanionHandle {

    private CompanionHandle() {}

    /** 在客户端世界里找当前同伴。 */
    public static Entity get(MinecraftClient mc) {
        if (mc.world == null) {
            return null;
        }
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof AICompanionEntity) {
                return e;
            }
        }
        return null;
    }

    /** 同伴格子坐标；没有实体就报玩家坐标。 */
    public static BlockPos pos(MinecraftClient mc) {
        Entity e = get(mc);
        if (e != null) {
            return e.getBlockPos();
        }
        return mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
    }
}
