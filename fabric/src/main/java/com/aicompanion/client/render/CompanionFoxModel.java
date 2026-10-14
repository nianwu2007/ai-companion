package com.aicompanion.client.render;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * 挂在玩家模型上的“狐狸零件”：一对耳朵、一束后发、一条三节大尾巴。
 * 全部用 fox_parts.png 上几块纯色区域取 UV，不依赖任何外部模型库。
 */
public class CompanionFoxModel extends Model {

    public final ModelPart leftEar;
    public final ModelPart rightEar;
    public final ModelPart hair;
    public final ModelPart tailBase;
    public final ModelPart tailMid;
    public final ModelPart tailTip;

    public CompanionFoxModel(ModelPart root) {
        super(RenderLayer::getEntityCutoutNoCull);
        this.leftEar = root.getChild("left_ear");
        this.rightEar = root.getChild("right_ear");
        this.hair = root.getChild("hair");
        this.tailBase = root.getChild("tail_base");
        this.tailMid = this.tailBase.getChild("tail_mid");
        this.tailTip = this.tailMid.getChild("tail_tip");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();

        // ---- 一对狐耳（外橙毛，内里一块浅色耳心）
        ModelPartData earR = root.addChild("right_ear", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-0.09f, -0.30f, -0.05f, 0.18f, 0.30f, 0.10f),
                ModelTransform.pivot(-0.13f, 1.50f, 0.0f));
        earR.addChild("inner", ModelPartBuilder.create()
                        .uv(16, 0).cuboid(-0.062f, -0.255f, -0.028f, 0.124f, 0.22f, 0.03f),
                ModelTransform.pivot(0.0f, 0.0f, -0.055f));

        ModelPartData earL = root.addChild("left_ear", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-0.09f, -0.30f, -0.05f, 0.18f, 0.30f, 0.10f),
                ModelTransform.pivot(0.13f, 1.50f, 0.0f));
        earL.addChild("inner", ModelPartBuilder.create()
                        .uv(16, 0).cuboid(-0.062f, -0.255f, -0.028f, 0.124f, 0.22f, 0.03f),
                ModelTransform.pivot(0.0f, 0.0f, -0.055f));

        // ---- 后发：从后脑垂到肩胛的一条长发
        root.addChild("hair", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-0.14f, -0.44f, 0.0f, 0.28f, 0.44f, 0.06f),
                ModelTransform.pivot(0.0f, 1.46f, 0.10f));

        // ---- 尾巴：三节，节节收细，尾尖用白毛
        ModelPartData tailBase = root.addChild("tail_base", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-0.09f, -0.09f, 0.0f, 0.18f, 0.18f, 0.30f),
                ModelTransform.pivot(0.0f, 0.95f, 0.14f));
        ModelPartData tailMid = tailBase.addChild("tail_mid", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-0.08f, -0.08f, 0.0f, 0.16f, 0.16f, 0.28f),
                ModelTransform.pivot(0.0f, 0.0f, 0.30f));
        tailMid.addChild("tail_tip", ModelPartBuilder.create()
                        .uv(0, 32).cuboid(-0.07f, -0.07f, 0.0f, 0.14f, 0.14f, 0.26f),
                ModelTransform.pivot(0.0f, 0.0f, 0.28f));

        return TexturedModelData.of(data, 64, 64);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay,
                       float red, float green, float blue, float alpha) {
        // 实际渲染由 CompanionFoxFeature 分部件完成（耳/发跟头转，尾巴挂在身后）。
    }
}
