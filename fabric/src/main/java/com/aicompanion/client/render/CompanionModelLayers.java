package com.aicompanion.client.render;

import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

/** 我们自己烘的模型层：狐狸零件（耳/尾/后发）。 */
public final class CompanionModelLayers {

    public static final EntityModelLayer FOX_PARTS =
            new EntityModelLayer(new Identifier("aicompanion", "fox_parts"), "main");

    private CompanionModelLayers() {
    }
}
