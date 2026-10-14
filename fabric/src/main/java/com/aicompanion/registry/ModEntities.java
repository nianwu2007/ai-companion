package com.aicompanion.registry;

import com.aicompanion.AiCompanionMod;
import com.aicompanion.entity.AICompanionEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/** 实体类型 + 属性注册。 */
public final class ModEntities {

    private ModEntities() {}

    public static final EntityType<AICompanionEntity> AI_COMPANION = Registry.register(
            Registries.ENTITY_TYPE,
            AiCompanionMod.id("ai_companion"),
            EntityType.Builder.create(AICompanionEntity::new, SpawnGroup.MISC)
                    .setDimensions(0.6f, 1.8f)
                    .maxTrackingRange(96)
                    .trackingTickInterval(2)
                    .build("ai_companion"));

    public static void register() {
        FabricDefaultAttributeRegistry.register(AI_COMPANION, AICompanionEntity.createCompanionAttributes());
        AiCompanionMod.LOG.info("[AICompanion] entity registered: {}", AiCompanionMod.id("ai_companion"));
    }
}
