package com.cobbleverse.cobbleboard.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.class)
public interface DisplayEntityInvoker {
    @Invoker("setBillboardMode")
    void cobbleboard$setBillboardMode(DisplayEntity.BillboardMode mode);

    @Invoker("setTransformation")
    void cobbleboard$setTransformation(AffineTransformation transformation);

    @Invoker("setViewRange")
    void cobbleboard$setViewRange(float viewRange);
}
