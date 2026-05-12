package org.booklore.service.downloads.adapter;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.plugin.CustomWebPluginAdapter;
import org.booklore.service.downloads.adapter.plugin.CustomWebPluginLoader;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@RequiredArgsConstructor
public class DownloadAdapterRegistry {

    private final List<DownloadSourceAdapter> builtInAdapters;
    private final CustomWebPluginLoader customWebPluginLoader;
    private final List<CustomWebPluginAdapter> customAdapters = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        reloadCustomPlugins();
    }

    public void reloadCustomPlugins() {
        customAdapters.clear();
        customAdapters.addAll(customWebPluginLoader.loadAdapters());
    }

    public DownloadSourceAdapter adapterFor(DownloadSourceEntity source) {
        if (source.getType() == DownloadSourceType.CUSTOM_WEB_PLUGIN) {
            return customAdapters.stream()
                    .filter(adapter -> adapter.supports(source))
                    .findFirst()
                    .orElseThrow(() -> new DownloadSourceException("No custom download plugin available for source " + source.getName()));
        }

        return builtInAdapters.stream()
                .filter(adapter -> !(adapter instanceof CustomWebPluginAdapter))
                .filter(adapter -> adapter.supports(source))
                .findFirst()
                .orElseThrow(() -> new DownloadSourceException("No download adapter available for source type " + source.getType()));
    }
}
