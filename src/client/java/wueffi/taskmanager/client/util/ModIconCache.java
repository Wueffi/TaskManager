package wueffi.taskmanager.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class ModIconCache {

    private static final ModIconCache INSTANCE = new ModIconCache();
    public static ModIconCache getInstance() { return INSTANCE; }
    public static final Logger LOGGER = LoggerFactory.getLogger("taskmanager");

    private static final Identifier DEFAULT_ICON   = Identifier.of("taskmanager", "default.png");
    private static final Identifier MINECRAFT_ICON = Identifier.of("taskmanager", "minecraft.png");
    private static final Identifier FABRIC_ICON    = Identifier.of("taskmanager", "fabric.png");

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
                    NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "taskmanager", img);

                    Identifier id = Identifier.of("taskmanager", "modicon/" + modId.replace(":", "_"));
                    MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);

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