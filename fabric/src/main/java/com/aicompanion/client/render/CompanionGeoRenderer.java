package com.aicompanion.client.render;

import com.aicompanion.entity.AICompanionEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 用 GeckoLib 渲染小怜 —— 整套酒狐身体替换原来的玩家模型 + 狐狸零件叠加。
 */
public class CompanionGeoRenderer extends GeoEntityRenderer<AICompanionEntity> {

    public CompanionGeoRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new CompanionGeoModel());
    }
}
