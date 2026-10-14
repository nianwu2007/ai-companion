package com.aicompanion.client.render;

import com.aicompanion.entity.AICompanionEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 用 GeckoLib 渲染小怜 —— 整套酒狐身体替换原来的玩家模型 + 狐狸零件叠加。
 *
 * 两个关键处理：
 * 1) 酒狐 geo 里同时塞了「人形女仆」和「狐狸形态」两套骨骼（FOX 子树），TLM 靠代码切换
 *    显隐，我们直接渲染会人狐重叠、还多出一只动物 —— 这里把 FOX 子树整支隐藏。
 * 2) 酒狐模型实际约 2.5 格高，比玩家 1.8 格高一大截，缩到 0.7 倍约 1.75 格。
 */
public class CompanionGeoRenderer extends GeoEntityRenderer<AICompanionEntity> {

    private boolean foxHidden = false;

    public CompanionGeoRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new CompanionGeoModel());
        this.withScale(0.7f);
    }

    @Override
    public void preRender(MatrixStack poseStack, AICompanionEntity animatable, BakedGeoModel model,
                          VertexConsumerProvider bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        if (!foxHidden) {
            this.getGeoModel().getBone("FOX").ifPresent(b -> {
                b.setHidden(true);
                b.setChildrenHidden(true);
            });
            foxHidden = true;
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
