package com.aicompanion.client.render;

import com.aicompanion.AiCompanionMod;
import com.aicompanion.config.AiConfig;
import com.aicompanion.entity.AICompanionEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * 用原版玩家模型渲染同伴 —— 她就是"一个玩家"，只是不占服务端位置。
 * 皮肤可在 config/aicompanion.json 的 companion.texture 里换成自己的。
 */
public class CompanionEntityRenderer
        extends LivingEntityRenderer<AICompanionEntity, PlayerEntityModel<AICompanionEntity>> {

    private Identifier texture = new Identifier("minecraft", "textures/entity/player/wide/steve.png");

    public CompanionEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx,
                new PlayerEntityModel<>(
                        ctx.getPart(AiConfig.INSTANCE.companion.slimArms
                                ? EntityModelLayers.PLAYER_SLIM : EntityModelLayers.PLAYER),
                        AiConfig.INSTANCE.companion.slimArms),
                0.5f);
        this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
        // 酒狐零件：狐耳 + 后发 + 尾巴
        this.addFeature(new CompanionFoxFeature(this, ctx.getPart(CompanionModelLayers.FOX_PARTS)));
        this.texture = parseTexture(AiConfig.INSTANCE.companion.texture);
    }

    private static Identifier parseTexture(String spec) {
        try {
            if (spec == null || spec.isEmpty()) {
                return new Identifier("minecraft", "textures/entity/player/wide/steve.png");
            }
            int colon = spec.indexOf(':');
            if (colon > 0) {
                return new Identifier(spec.substring(0, colon), spec.substring(colon + 1));
            }
            return new Identifier("minecraft", spec);
        } catch (RuntimeException e) {
            AiCompanionMod.LOG.warn("[AICompanion] 皮肤路径无效，回退 Steve: {}", spec);
            return new Identifier("minecraft", "textures/entity/player/wide/steve.png");
        }
    }

    /** 皮肤换过之后重新读一次配置。 */
    public void refreshTexture() {
        this.texture = parseTexture(AiConfig.INSTANCE.companion.texture);
    }

    @Override
    public Identifier getTexture(AICompanionEntity entity) {
        return texture;
    }

    @Override
    protected boolean hasLabel(AICompanionEntity entity) {
        return AiConfig.INSTANCE.companion.showNameTag && !entity.isInvisible();
    }

    @Override
    public void render(AICompanionEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        // 注视目标 → 让头部朝目标转
        if (entity.getLookTarget() != null) {
            entity.faceTowards(entity.getLookTarget());
        }
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }
}
