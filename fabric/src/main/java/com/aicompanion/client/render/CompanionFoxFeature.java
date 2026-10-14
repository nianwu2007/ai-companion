package com.aicompanion.client.render;

import com.aicompanion.entity.AICompanionEntity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/**
 * 把狐狸零件画到同伴身上：耳朵和后发跟着头转，尾巴在身后轻摆。
 * 用独立贴图，不动原版玩家皮的任何 UV。
 */
public class CompanionFoxFeature
        extends FeatureRenderer<AICompanionEntity, PlayerEntityModel<AICompanionEntity>> {

    private static final Identifier TEXTURE =
            new Identifier("aicompanion", "textures/entity/fox_parts.png");

    /** 玩家模型头部枢轴的高度（格）。 */
    private static final float HEAD_PIVOT = 1.5f;

    private final CompanionFoxModel model;

    public CompanionFoxFeature(
            FeatureRendererContext<AICompanionEntity, PlayerEntityModel<AICompanionEntity>> context,
            ModelPart root) {
        super(context);
        this.model = new CompanionFoxModel(root);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                       AICompanionEntity entity, float limbAngle, float limbDistance, float tickDelta,
                       float animationProgress, float headYaw, float headPitch) {
        if (entity.isInvisible()) {
            return;
        }
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
        float t = animationProgress;
        float sway = (float) Math.sin(t * 0.14f);

        // ---- 耳朵 + 后发：绕脖子转，跟着头走
        matrices.push();
        matrices.translate(0.0f, HEAD_PIVOT, 0.0f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(headYaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotation(headPitch));
        matrices.translate(0.0f, -HEAD_PIVOT, 0.0f);

        this.model.leftEar.pitch = -0.20f + (float) Math.sin(t * 0.09f) * 0.06f;
        this.model.leftEar.yaw = 0.20f;
        this.model.leftEar.roll = -0.10f;
        this.model.rightEar.pitch = -0.20f + (float) Math.sin(t * 0.09f + 0.8f) * 0.06f;
        this.model.rightEar.yaw = -0.20f;
        this.model.rightEar.roll = 0.10f;
        this.model.hair.pitch = 0.05f + (float) Math.sin(t * 0.07f) * 0.035f;

        this.model.leftEar.render(matrices, vc, light, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        this.model.rightEar.render(matrices, vc, light, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        this.model.hair.render(matrices, vc, light, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        matrices.pop();

        // ---- 尾巴：从腰后伸出来，三节一起摇
        matrices.push();
        this.model.tailBase.pitch = 0.28f + sway * 0.10f;
        this.model.tailBase.yaw = (float) Math.sin(t * 0.11f) * 0.22f;
        this.model.tailMid.pitch = 0.20f + sway * 0.09f;
        this.model.tailMid.roll = (float) Math.sin(t * 0.13f) * 0.10f;
        this.model.tailTip.pitch = 0.16f + sway * 0.07f;
        this.model.tailBase.render(matrices, vc, light, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        matrices.pop();
    }
}
