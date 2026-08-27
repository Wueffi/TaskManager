package wueffi.taskmanager.client.mixin;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.NetworkPacketProfiler;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
    private void taskmanager$onChannelRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        NetworkPacketProfiler.getInstance().recordInbound(packet);
    }

    @Inject(method = "sendInternal", at = @At("HEAD"))
    private void taskmanager$onSendInternal(Packet<?> packet, ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
        NetworkPacketProfiler.getInstance().recordOutbound(packet);
    }
}
