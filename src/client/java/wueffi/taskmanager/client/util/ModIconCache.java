package wueffi.taskmanager.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class ModIconCache {

    private static final ModIconCache INSTANCE = new ModIconCache();
    public static ModIconCache getInstance() { return INSTANCE; }
    public static final Logger LOGGER = LoggerFactory.getLogger("taskmanager");

    private static final Identifier DEFAULT_ICON   = Identifier.fromNamespaceAndPath("taskmanager", "default.png");
    private static final Identifier MINECRAFT_ICON = Identifier.fromNamespaceAndPath("taskmanager", "minecraft.png");
    private static final Identifier FABRIC_ICON    = Identifier.fromNamespaceAndPath("taskmanager", "fabric.png");

    private final Map<String, Identifier> cache = new HashMap<>();

    public Identifier getIcon(String modId) {
        return cache.computeIfAbsent(modId, this::loadIcon);
    }

    private Identifier loadIcon(String modId) {
        if (modId.equals("minecraft")) return MINECRAFT_ICON;
        if (modId.startsWith("fabric-") || modId.equals("fabricloader")) return FABRIC_ICON;

        try {
            ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElse(null);
            if (mod == null) return DEFAULT_ICON;

            String iconPath = mod.getMetadata().getIconPath(32)
                    .or(() -> mod.getMetadata().getIconPath(64))
                    .or(() -> mod.getMetadata().getIconPath(128)).orElse(null);
            if (iconPath == null) return DEFAULT_ICON;

            return mod.findPath(iconPath).map(path -> {
                try (InputStream is = Files.newInputStream(path)) {

                    NativeImage img = NativeImage.read(is);
                    DynamicTexture tex = new DynamicTexture(() -> "taskmanager", img);

                    Identifier id = Identifier.fromNamespaceAndPath("taskmanager", "modicon/" + modId.replace(":", "_"));
                    Minecraft.getInstance().getTextureManager().register(id, tex);

                    return id;
                } catch (Exception e) {
                    return DEFAULT_ICON;
                }
            }).orElse(DEFAULT_ICON);
        } catch (Exception e) {
            LoggerFactory.getLogger("taskmanager").debug("Failed to load icon for {}: {}", modId, e.getMessage());
        }

        return DEFAULT_ICON;
    }
}