package wueffi.taskmanager.client.mixin;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ChunkWorkProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.ModClassIndex;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "createBiomes", at = @At("HEAD"))
    private void taskmanager$beginPopulateBiomes(CallbackInfoReturnable<?> cir) {
        taskmanager$beginPhase("populateBiomes");
    }

    @Inject(method = "createBiomes", at = @At("RETURN"))
    private void taskmanager$endPopulateBiomes(CallbackInfoReturnable<?> cir) {
        taskmanager$endPhase();
    }

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void taskmanager$beginGenerateFeatures(CallbackInfo ci) {
        taskmanager$beginPhase("generateFeatures");
    }

    @Inject(method = "applyBiomeDecoration", at = @At("RETURN"))
    private void taskmanager$endGenerateFeatures(CallbackInfo ci) {
        taskmanager$endPhase();
    }

    private void taskmanager$beginPhase(String phase) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ChunkGenerator generator = (ChunkGenerator) (Object) this;
        String ownerMod = ModClassIndex.getModForClassName(generator.getClass());
        ChunkWorkProfiler.getInstance().beginPhase((ownerMod == null ? "minecraft" : ownerMod) + " | " + phase);
    }

    private void taskmanager$endPhase() {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ChunkWorkProfiler.getInstance().endPhase();
    }
}
