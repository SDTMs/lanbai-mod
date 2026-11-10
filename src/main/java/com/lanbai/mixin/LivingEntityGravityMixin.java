package com.lanbai.mixin;

import com.lanbai.core.GravityStore;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 真·重力修改。
 * 在 LivingEntity#travel 结束后按因子补偿竖直速度：
 *   factor 0 = 失重，0.5 = 半重力，2 = 双倍重力。
 *
 * defaultRequire=0，注入失败只警告不崩服。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGravityMixin {

    @Inject(method = "travel", at = @At("RETURN"))
    private void lanbai$applyGravity(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        float f = GravityStore.get(self);
        if (f == 1.0f) return;
        Vec3 mv = self.getDeltaMovement();
        self.setDeltaMovement(mv.x, mv.y + (f - 1.0) * 0.08, mv.z);
    }
}
