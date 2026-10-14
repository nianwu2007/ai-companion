package com.aicompanion.server;

import com.aicompanion.AiCompanionMod;
import com.aicompanion.config.AiConfig;
import com.aicompanion.entity.AICompanionEntity;
import com.aicompanion.registry.ModEntities;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 服务端生成器 —— 给小怜一个"真身"。
 *
 * 之前她是纯客户端实体，服务端根本不知道她存在，所以原版 AI 一跑就空指针。
 * 现在改成真正的服务端实体：在服务端世界里 spawn，AI 走原版那套（MobNavigation），
 * 寻路、跳跃、避障全部免费拿到，客户端只负责渲染。
 */
public final class CompanionSpawner {

    private CompanionSpawner() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                ensureFor(handler.getPlayer());
            } catch (Throwable t) {
                AiCompanionMod.LOG.warn("[AICompanion] 生成同伴失败: {}", t.toString());
            }
        });
    }

    /** 确保这个玩家身边有一只属于他的小怜，没有就造一只。 */
    public static AICompanionEntity ensureFor(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        AICompanionEntity existing = findOwned(world, player);
        if (existing != null) {
            return existing;
        }
        AICompanionEntity e = new AICompanionEntity(ModEntities.AI_COMPANION, world);
        double yaw = Math.toRadians(player.getYaw());
        Vec3d spot = player.getPos().add(-Math.cos(yaw) * 1.5, 0, -Math.sin(yaw) * 1.5);
        e.refreshPositionAndAngles(spot.x, player.getY(), spot.z, player.getYaw(), 0f);
        e.setOwner(player);
        e.setCustomName(Text.literal(AiConfig.INSTANCE.companion.name));
        e.setCustomNameVisible(true);
        e.setPersistent();
        world.spawnEntity(e);
        AiCompanionMod.LOG.info("[AICompanion] 服务端已生成同伴：{}", AiConfig.INSTANCE.companion.name);
        return e;
    }

    private static AICompanionEntity findOwned(ServerWorld world, ServerPlayerEntity player) {
        Box box = player.getBoundingBox().expand(96);
        List<AICompanionEntity> list = world.getEntitiesByClass(AICompanionEntity.class, box,
                e -> e.getOwnerUuid() != null && e.getOwnerUuid().equals(player.getUuid()));
        return list.isEmpty() ? null : list.get(0);
    }
}
