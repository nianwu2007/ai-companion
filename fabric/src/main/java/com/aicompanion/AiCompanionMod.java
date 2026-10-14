package com.aicompanion;

import com.aicompanion.config.AiConfig;
import com.aicompanion.registry.ModEntities;
import com.aicompanion.server.CompanionSpawner;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiCompanionMod implements ModInitializer {

    public static final String MOD_ID = "aicompanion";
    public static final String VERSION = "1.0.0";
    public static final Logger LOG = LoggerFactory.getLogger("AICompanion");

    public static AiConfig cfg() {
        return AiConfig.INSTANCE;
    }

    @Override
    public void onInitialize() {
        AiConfig.load(FabricLoader.getInstance().getConfigDir().resolve("aicompanion.json"));
        ModEntities.register();
        CompanionSpawner.register();
        LOG.info("[AICompanion] {} loaded. 行动端点={} 模型={}｜聊天端点={} 模型={}",
                VERSION, cfg().llm.ollamaUrl, cfg().llm.ollamaModel, cfg().llm.deepseekUrl, cfg().llm.deepseekModel);
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }
}
