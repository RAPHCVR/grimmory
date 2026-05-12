package org.booklore.service.downloads.adapter.plugin;

import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;

public interface CustomWebPluginAdapter extends DownloadSourceAdapter {

    String pluginId();

    @Override
    default DownloadSourceType sourceType() {
        return DownloadSourceType.CUSTOM_WEB_PLUGIN;
    }
}
