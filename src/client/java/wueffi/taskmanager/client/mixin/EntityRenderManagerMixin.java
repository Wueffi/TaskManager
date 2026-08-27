package wueffi.taskmanager.client.mixin;

import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.EntityCostProfiler;
import wueffi.taskmanager.client.ProfilerManager;

@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {

    @Inject(method = "getAndUpdateRenderState", at = @At("HEAD"))
    private <E extends Entity> void taskmanager$beginEntityRenderPrep(E entity, float tickProgress, CallbackInfoReturnable<?> cir) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        EntityCostProfiler.getInstance().beginEntityRenderPrep(entity);
    }

    @Inject(method = "getAndUpdateRenderState", at = @At("TAIL"))
    private void taskmanager$endEntityRenderPrep(CallbackInfoReturnable<?> cir) {
        EntityCostProfiler.getInstance().endEntityRenderPrep();
    }
}
