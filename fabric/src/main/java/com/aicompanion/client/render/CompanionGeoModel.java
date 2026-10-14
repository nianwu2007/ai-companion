package com.aicompanion.client.render;

import com.aicompanion.entity.AICompanionEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * 小怜的 GeckoLib 模型定义：直接吃 TLM 整合包里的酒狐 geo 模型 / 动画 / 贴图。
 * 资源已从 TLM jar 提取到 aicompanion 命名空间下（geo/animations/textures）。
 */
public class CompanionGeoModel extends GeoModel<AICompanionEntity> {

    private static final Identifier MODEL =
            new Identifier("aicompanion", "geo/entity/winefox.geo.json");
    private static final Identifier TEXTURE =
            new Identifier("aicompanion", "textures/entity/winefox.png");
    private static final Identifier ANIMATION =
            new Identifier("aicompanion", "animations/entity/winefox.animation.json");

    @Override
    public Identifier getModelResource(AICompanionEntity animatable) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(AICompanionEntity animatable) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(AICompanionEntity animatable) {
        return ANIMATION;
    }
}
