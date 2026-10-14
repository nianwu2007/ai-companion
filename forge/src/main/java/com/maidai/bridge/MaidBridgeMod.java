package com.maidai.bridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * MaidAI Bridge — a client-side HTTP control/telemetry API for local AI companion automation.
 *
 * The mod itself knows nothing about the AI. It just exposes the game to a local agent
 * (and to Baritone, TLM's maid AI, TTS/STT gateway) over 127.0.0.1:8790.
 */
@Mod(MaidBridgeMod.MODID)
public final class MaidBridgeMod {

    public static final String MODID = "maidai_bridge";
    public static final String VERSION = "1.0.0";

    private static final Logger LOG = LogUtils.getLogger();

    private static HttpApi API;

    public MaidBridgeMod() {
        // Register client-only work only on the physical client, so no client class is ever
        // touched (or loaded) on a dedicated server.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        }
        LOG.info("[MaidBridge] constructed (dist={})", FMLEnvironment.dist);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        event.enqueueWork(() -> {
            try {
                API = new HttpApi();
                API.start();
                LOG.info("[MaidBridge] HTTP API listening on 127.0.0.1:{}", API.port());
            } catch (Throwable t) {
                LOG.error("[MaidBridge] failed to start HTTP API", t);
            }
        });
    }
}
