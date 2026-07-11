package wueffi.taskmanager.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Map;
import java.util.stream.Stream;

public class ModClassIndex {

    private static final String UNKNOWN_SENTINEL = "\u0000";
    private static final int MAX_CLASS_CACHE_ENTRIES = 65_536;
    private static final int MAX_ROOT_CACHE_ENTRIES = 1_024;
    private static final int MAX_PACKAGE_CACHE_ENTRIES = 32_768;
    private static final Map<String, String> cache = BoundedMaps.synchronizedLru(MAX_CLASS_CACHE_ENTRIES);
    private static final Map<String, String> normalizedRootToMod = BoundedMaps.synchronizedLru(MAX_ROOT_CACHE_ENTRIES);
    private static final Map<String, String> packageCache = BoundedMaps.synchronizedLru(MAX_PACKAGE_CACHE_ENTRIES);
    private static boolean built = false;

    public static void build() {
        if (built) return;
        built = true;

        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String modId = container.getMetadata().getId();
            for (Path root : container.getRootPaths()) {
                try {
                    String normalized = normalizePath(root.toUri().toURL().toString());
                    BoundedMaps.put(normalizedRootToMod, normalized, modId);
                    scanRootForClassPackages(root, modId);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static String getModForClassName(Class<?> clazz) {
        if (!built) build();

        String className = sanitizeClassName(clazz.getName());
        if (className == null || className.isBlank()) {
            return null;
        }
        String cached = BoundedMaps.get(cache, className);
        if (cached != null) return decodeCachedValue(cached);
        String packageHit = lookupPackageCache(className);
        if (packageHit != null) {
            BoundedMaps.put(cache, className, encodeCachedValue(packageHit));
            return packageHit;
        }

        String classSource = null;
        try {
            CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                classSource = normalizePath(cs.getLocation().toString());
            }
        } catch (Exception ignored) {
        }

        if (classSource != null) {
            synchronized (normalizedRootToMod) {
                for (Map.Entry<String, String> entry : normalizedRootToMod.entrySet()) {
                    if (classSource.equals(entry.getKey()) || classSource.startsWith(entry.getKey())) {
                        BoundedMaps.put(cache, className, encodeCachedValue(entry.getValue()));
                        cachePackagePrefixes(className, entry.getValue());
                        return entry.getValue();
                    }
                }
            }
        }

        BoundedMaps.put(cache, className, UNKNOWN_SENTINEL);
        return null;
    }

    public static String getModForClassName(String rawClassName) {
        if (!built) build();

        String className = sanitizeClassName(rawClassName);
        if (className == null || className.isBlank()) {
            return null;
        }

        String cached = BoundedMaps.get(cache, className);
        if (cached != null) {
            return decodeCachedValue(cached);
        }
        String packageHit = lookupPackageCache(className);
        if (packageHit != null) {
            BoundedMaps.put(cache, className, encodeCachedValue(packageHit));
            return packageHit;
        }

        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                Class<?> clazz = Class.forName(className, false, contextLoader);
                return getModForClassName(clazz);
            } catch (Throwable ignored) {
            }
        }

        try {
            Class<?> clazz = Class.forName(className, false, ModClassIndex.class.getClassLoader());
            return getModForClassName(clazz);
        } catch (Throwable ignored) {
            BoundedMaps.put(cache, className, UNKNOWN_SENTINEL);
            return null;
        }
    }

    private static String sanitizeClassName(String rawClassName) {
        if (rawClassName == null || rawClassName.isBlank()) {
            return rawClassName;
        }

        String className = rawClassName
                .replace('/', '.')
                .replaceAll("\\$\\$Lambda.*", "")
                .replaceAll("\\$\\d+$", "")
                .replaceAll("\\$Subclass\\d+", "")
                .replaceAll("\\$MixinProxy.*", "");

        if (!className.startsWith("[")) {
            return className;
        }

        while (className.startsWith("[")) {
            className = className.substring(1);
        }

        if (className.startsWith("L") && className.endsWith(";")) {
            return className.substring(1, className.length() - 1).replace('/', '.');
        }

        return null;
    }

    private static String normalizePath(String url) {
        return url
                .replace("jar:file:///", "")
                .replace("jar:file://", "")
                .replace("jar:file:/", "")
                .replace("file:///", "")
                .replace("file://", "")
                .replace("file:/", "")
                .replace("!/", "")
                .replace("!", "")
                .toLowerCase();
    }

    private static String lookupPackageCache(String className) {
        int separator = className.length();
        while (separator > 0) {
            separator = className.lastIndexOf('.', separator - 1);
            if (separator <= 0) {
                break;
            }
            String prefix = className.substring(0, separator);
            String cached = BoundedMaps.get(packageCache, prefix);
            if (cached != null) {
                return UNKNOWN_SENTINEL.equals(cached) ? null : cached;
            }
        }
        return null;
    }

    private static void cachePackagePrefixes(String className, String modId) {
        int separator = className.lastIndexOf('.');
        while (separator > 0) {
            String prefix = className.substring(0, separator);
            cachePackagePrefix(prefix, modId);
            separator = className.lastIndexOf('.', separator - 1);
        }
    }

    private static void scanRootForClassPackages(Path root, String modId) {
        if (root == null || modId == null || modId.isBlank() || !Files.exists(root)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(path -> path.endsWith(".class"))
                    .map(ModClassIndex::classNameFromRelativePath)
                    .filter(className -> className != null && !className.isBlank())
                    .forEach(className -> {
                        String existing = BoundedMaps.get(cache, className);
                        if (existing == null) {
                            BoundedMaps.put(cache, className, encodeCachedValue(modId));
                        } else {
                            String decoded = decodeCachedValue(existing);
                            if (decoded != null && !decoded.equals(modId)) {
                                BoundedMaps.put(cache, className, UNKNOWN_SENTINEL);
                            }
                        }
                        cachePackagePrefixes(className, modId);
                    });
        } catch (IOException | SecurityException ignored) {
        }
    }

    private static String classNameFromRelativePath(String path) {
        if (path == null || !path.endsWith(".class")) {
            return null;
        }
        String className = path.substring(0, path.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
        if (className.endsWith(".module-info") || className.endsWith(".package-info") || "module-info".equals(className) || "package-info".equals(className)) {
            return null;
        }
        return sanitizeClassName(className);
    }

    private static void cachePackagePrefix(String prefix, String modId) {
        String existing = BoundedMaps.get(packageCache, prefix);
        if (existing == null) {
            BoundedMaps.put(packageCache, prefix, modId);
            return;
        }
        String decoded = UNKNOWN_SENTINEL.equals(existing) ? null : existing;
        if (decoded != null && !decoded.equals(modId)) {
            BoundedMaps.put(packageCache, prefix, UNKNOWN_SENTINEL);
        }
    }

    private static String encodeCachedValue(String modId) {
        return modId == null ? UNKNOWN_SENTINEL : modId;
    }

    private static String decodeCachedValue(String cachedValue) {
        return UNKNOWN_SENTINEL.equals(cachedValue) ? null : cachedValue;
    }
}
