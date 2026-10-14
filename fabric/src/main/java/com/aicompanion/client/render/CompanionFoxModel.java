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

        // ---- 一对狐耳：头顶两侧，各向上冒 6 像素（这里是像素，1 格 = 16）
        ModelPartData earR = root.addChild("right_ear", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-1.5f, -6.0f, -1.0f, 3.0f, 6.0f, 2.0f),
                ModelTransform.pivot(-3.0f, 0.0f, 0.0f));
        earR.addChild("inner", ModelPartBuilder.create()
                        .uv(32, 32).cuboid(-1.0f, -5.0f, -0.35f, 2.0f, 4.0f, 0.4f),
                ModelTransform.pivot(0.0f, 0.0f, -1.05f));

        ModelPartData earL = root.addChild("left_ear", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-1.5f, -6.0f, -1.0f, 3.0f, 6.0f, 2.0f),
                ModelTransform.pivot(3.0f, 0.0f, 0.0f));
        earL.addChild("inner", ModelPartBuilder.create()
                        .uv(32, 32).cuboid(-1.0f, -5.0f, -0.35f, 2.0f, 4.0f, 0.4f),
                ModelTransform.pivot(0.0f, 0.0f, -1.05f));

        // ---- 后发：贴在后脑，从发顶垂到背心，长 14 像素
        root.addChild("hair", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-3.0f, -6.0f, 0.0f, 6.0f, 14.0f, 2.0f),
                ModelTransform.pivot(0.0f, 0.0f, 4.0f));

        // ---- 尾巴：三节向后伸 23 像素，节节收细，尾尖换白毛
        ModelPartData tailBase = root.addChild("tail_base", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-2.0f, -2.0f, 0.0f, 4.0f, 4.0f, 8.0f),
                ModelTransform.pivot(0.0f, -10.0f, 3.0f));
        ModelPartData tailMid = tailBase.addChild("tail_mid", ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-1.5f, -1.5f, 0.0f, 3.0f, 3.0f, 8.0f),
                ModelTransform.pivot(0.0f, 0.0f, 8.0f));
        tailMid.addChild("tail_tip", ModelPartBuilder.create()
                        .uv(32, 0).cuboid(-1.5f, -1.5f, 0.0f, 3.0f, 3.0f, 7.0f),
                ModelTransform.pivot(0.0f, 0.0f, 8.0f));

        return TexturedModelData.of(data, 64, 64);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay,
                       float red, float green, float blue, float alpha) {
        // 实际渲染由 CompanionFoxFeature 分部件完成（耳/发跟头转，尾巴挂在身后）。
    }
}
