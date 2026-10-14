package com.aicompanion.engine;

import com.aicompanion.AiCompanionMod;
import com.aicompanion.config.AiConfig;
import com.aicompanion.entity.AICompanionEntity;
import com.aicompanion.registry.ModEntities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同伴实体的"实体化" —— 让玩家看得见它站在那儿。
 *
 * 注意：实体只存在于客户端世界里（服务端没有它，也不可能有），
 * 它不参与任何真实方块交互，纯粹是可见的化身 + 数据载体。
 * 真正干活的是 PlayerEngine 借用的本机玩家身体。
 * 所以这里给实体关掉重力/碰撞/物理，由我们每 tick 把它"贴"到玩家旁边，
 * 免得它掉进虚空或者被服务端当成作弊实体踢掉。
 */
public final class CompanionHandle {

    private CompanionHandle() {}

    private static Entity current;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(-700000);

    /** 取得当前同伴实体（可能为 null）。 */
    public static Entity get(MinecraftClient mc) {
        if (current != null && (mc.world == null || current.getWorld() != mc.world)) {
            current = null;
        }
        return current;
    }

    /** 没实体就造一个，放在玩家右边一格。 */
    public static Entity ensure(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            return null;
        }
        ClientWorld world = mc.world;
        Entity e = get(mc);
        if (e == null) {
            AiConfig.Companion cfg = AiConfig.INSTANCE.companion;
            AICompanionEntity companion = new AICompanionEntity(ModEntities.AI_COMPANION, world);
            Vec3d spot = spotNear(mc);
            companion.refreshPositionAndAngles(spot.x, spot.y, spot.z, mc.player.getYaw(), 0f);
            companion.setId(NEXT_ID.getAndDecrement());
            companion.setCustomName(Text.literal(cfg.name));
            companion.setCustomNameVisible(true);
            companion.setInvulnerable(true);
            companion.setNoGravity(true);
            companion.setSilent(false);
            world.addEntity(companion.getId(), companion);
            current = companion;
            AiCompanionMod.LOG.info("[AI同伴] 实体已就位：{}", cfg.name);
            return companion;
        }
        softlyFollow(mc, e);
        return e;
    }

    /** 每 tick 轻轻贴住玩家，只在走远时瞬移，避免抖动。 */
    public static void tick(MinecraftClient mc) {
        Entity e = get(mc);
        if (e == null || mc.player == null) {
            return;
        }
        if (e.isRemoved()) {
            current = null;
            return;
        }
        e.setNoGravity(true);
        e.fallDistance = 0f;
        softlyFollow(mc, e);
    }

    private static void softlyFollow(MinecraftClient mc, Entity e) {
        Vec3d want = spotNear(mc);
        double d = e.getPos().distanceTo(want);
        if (d > 12) {
            e.refreshPositionAndAngles(want.x, want.y, want.z, mc.player.getYaw(), 0f);
            return;
        }
        if (d > 2.5) {
            Vec3d step = want.subtract(e.getPos()).multiply(0.25);
            e.setVelocity(step.x, step.y, step.z);
        } else {
            e.setVelocity(0, 0, 0);
        }
        e.setYaw(mc.player.getYaw());
        e.setPitch(0f);
    }

    /** 玩家右手边一格半、脚踩实地。 */
    private static Vec3d spotNear(MinecraftClient mc) {
        Vec3d p = mc.player.getPos();
        double yaw = Math.toRadians(mc.player.getYaw());
        double dx = -Math.cos(yaw) * 1.5;
        double dz = -Math.sin(yaw) * 1.5;
        BlockPos base = BlockPos.ofFloored(p.x + dx, p.y, p.z + dz);
        BlockPos ground = base;
        for (int i = 0; i < 4 && mc.world.getBlockState(ground).isAir(); i++) {
            ground = ground.down();
        }
        if (mc.world.getBlockState(ground).isAir()) {
            ground = base;
        }
        return new Vec3d(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
    }

    public static void remove(MinecraftClient mc) {
        Entity e = get(mc);
        if (e != null) {
            e.discard();
        }
        current = null;
    }

    /** 同伴格子坐标（没有实体就报玩家坐标）。 */
    public static BlockPos pos(MinecraftClient mc) {
        Entity e = get(mc);
        return e != null ? e.getBlockPos() : (mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN);
    }
}
