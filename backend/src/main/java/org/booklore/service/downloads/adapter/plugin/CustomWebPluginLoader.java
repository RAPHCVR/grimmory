package org.booklore.service.downloads.adapter.plugin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomWebPluginLoader {

    private static final String PLUGIN_DIR = "download-plugins";

    private final AppProperties appProperties;
    private final List<URLClassLoader> activeClassLoaders = new CopyOnWriteArrayList<>();

    public synchronized List<CustomWebPluginAdapter> loadAdapters() {
        closeActiveClassLoaders();
        Path pluginDir = Path.of(appProperties.getPathConfig(), PLUGIN_DIR);
        if (Files.notExists(pluginDir)) {
            return List.of();
        }

        List<CustomWebPluginAdapter> adapters = new ArrayList<>();
        try (Stream<Path> files = Files.list(pluginDir)) {
            List<Path> jars = files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .toList();
            for (Path jar : jars) {
                adapters.addAll(loadAdaptersFromJar(jar));
            }
        } catch (IOException e) {
            log.warn("Failed to scan custom download plugin directory {}: {}", pluginDir, e.getMessage());
        }
        return List.copyOf(adapters);
    }

    private List<CustomWebPluginAdapter> loadAdaptersFromJar(Path jar) {
        try {
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
            activeClassLoaders.add(loader);
            List<CustomWebPluginAdapter> adapters = new ArrayList<>();
            ServiceLoader.load(CustomWebPluginAdapter.class, loader).forEach(adapter -> {
                log.info("Loaded custom download plugin '{}' from {}", adapter.pluginId(), jar);
                adapters.add(adapter);
            });
            return adapters;
        } catch (Exception e) {
            log.warn("Failed to load custom download plugin jar {}: {}", jar, e.getMessage());
            return List.of();
        }
    }

    private void closeActiveClassLoaders() {
        for (URLClassLoader loader : activeClassLoaders) {
            try {
                loader.close();
            } catch (IOException e) {
                log.debug("Failed to close custom download plugin classloader", e);
            }
        }
        activeClassLoaders.clear();
    }
}
