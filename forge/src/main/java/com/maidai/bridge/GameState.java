package com.maidai.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/** Snapshots of the local game state, returned as JSON to the local agent. */
public final class GameState {

    /** Baritone's Forge mod id is literally "baritoe" (upstream quirk). */
    private static final String BARITONE_MODID = "baritoe";

    private GameState() {
    }

    public static String mods(String query) {
        String q = query == null ? "" : query.toLowerCase();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"ok\":true,\"mods\":[");
        boolean first = true;
        for (var info : ModList.get().getMods()) {
            String id = info.getModId();
            String name = String.valueOf(info.getDisplayName());
            if (!q.isEmpty() && !id.toLowerCase().contains(q) && !name.toLowerCase().contains(q)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(Json.esc(id))
                    .append("\",\"name\":\"").append(Json.esc(name))
                    .append("\",\"version\":\"").append(Json.esc(String.valueOf(info.getVersion())))
                    .append("\"}");
        }
        return sb.append("]}").toString();
    }

    public static String dump() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        boolean inGame = (p != null && mc.level != null);

        StringBuilder b = new StringBuilder(1024);
        b.append("{\"ok\":true,\"inGame\":").append(inGame);
        b.append(",\"screen\":\"").append(mc.screen == null ? "none" : Json.esc(mc.screen.getClass().getSimpleName())).append('"');
        b.append(",\"mods\":{\"baritone\":").append(ModList.get().isLoaded(BARITONE_MODID))
                .append(",\"tlm\":").append(ModList.get().isLoaded("touhou_little_maid"))
                .append(",\"kubejs\":").append(ModList.get().isLoaded("kubejs")).append('}');

        if (p != null) {
            b.append(",\"player\":{\"name\":\"").append(Json.esc(p.getGameProfile().getName())).append('"')
                    .append(",\"x\":").append(Json.r(p.getX()))
                    .append(",\"y\":").append(Json.r(p.getY()))
                    .append(",\"z\":").append(Json.r(p.getZ()))
                    .append(",\"yaw\":").append(Json.r(p.getYRot()))
                    .append(",\"pitch\":").append(Json.r(p.getXRot()))
                    .append(",\"health\":").append(Json.r(p.getHealth()))
                    .append(",\"maxHealth\":").append(Json.r(p.getMaxHealth()))
                    .append(",\"food\":").append(p.getFoodData().getFoodLevel())
                    .append(",\"xpLevel\":").append(p.experienceLevel)
                    .append(",\"onGround\":").append(p.onGround())
                    .append(",\"sneaking\":").append(p.isShiftKeyDown())
                    .append(",\"blockX\":").append(p.blockPosition().getX())
                    .append(",\"blockY\":").append(p.blockPosition().getY())
                    .append(",\"blockZ\":").append(p.blockPosition().getZ())
                    .append(",\"dimension\":\"").append(mc.level == null ? "" : mc.level.dimension().location()).append('"')
                    .append('}');
        }

        if (mc.level != null) {
            b.append(",\"world\":{\"dayTime\":").append(mc.level.getDayTime())
                    .append(",\"gameTime\":").append(mc.level.getGameTime())
                    .append(",\"raining\":").append(mc.level.isRaining())
                    .append(",\"thundering\":").append(mc.level.isThundering())
                    .append('}');
        }

        // TLM maids anywhere the client can see (class name filter keeps us free of a TLM compile dep)
        List<Entity> maids = new ArrayList<>();
        int hostiles = 0;
        double nearestHostile = -1;
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == null) continue;
                if (e.getClass().getName().contains("touhoulittlemaid")) {
                    maids.add(e);
                    continue;
                }
                if (p != null && e instanceof Enemy && e.isAlive()) {
                    hostiles++;
                    double d = p.distanceTo(e);
                    if (nearestHostile < 0 || d < nearestHostile) nearestHostile = d;
                }
            }
        }

        b.append(",\"maids\":[");
        for (int i = 0; i < maids.size(); i++) {
            Entity e = maids.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":\"").append(Json.esc(e.getName().getString())).append('"')
                    .append(",\"custom\":").append(e.hasCustomName())
                    .append(",\"type\":\"").append(Json.esc(e.getClass().getSimpleName())).append('"')
                    .append(",\"x\":").append(Json.r(e.getX()))
                    .append(",\"y\":").append(Json.r(e.getY()))
                    .append(",\"z\":").append(Json.r(e.getZ()))
                    .append(",\"distance\":").append(p == null ? -1 : Json.r(p.distanceTo(e)))
                    .append(",\"health\":").append(e instanceof LivingEntity le ? Json.r(le.getHealth()) : -1)
                    .append('}');
        }
        b.append(']');

        b.append(",\"threat\":{\"hostiles\":").append(hostiles)
                .append(",\"nearestHostile\":").append(nearestHostile < 0 ? -1 : Json.r(nearestHostile))
                .append('}');

        return b.append('}').toString();
    }
}
