package wueffi.taskmanager.client.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.EntityCostProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void taskmanager$beginEntityTick(CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        EntityCostProfiler.getInstance().beginEntityTick((Entity) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void taskmanager$endEntityTick(CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        EntityCostProfiler.getInstance().endEntityTick();
    }
}
