package wueffi.taskmanager.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.EntityCostProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderManagerMixin {

    @Inject(method = "extractEntity", at = @At("HEAD"))
    private <E extends Entity> void taskmanager$beginEntityRenderPrep(E entity, float tickProgress, CallbackInfoReturnable<?> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        EntityCostProfiler.getInstance().beginEntityRenderPrep(entity);
    }

    @Inject(method = "extractEntity", at = @At("TAIL"))
    private void taskmanager$endEntityRenderPrep(CallbackInfoReturnable<?> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        EntityCostProfiler.getInstance().endEntityRenderPrep();
    }
}
