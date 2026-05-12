package org.booklore.model.dto.request.downloads;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.booklore.model.enums.DownloadSourceType;

@Data
public class DownloadSourceRequest {
    @NotBlank
    private String name;

    @NotNull
    private DownloadSourceType type;

    private String credentialsJson;
    private String configJson;
    private Boolean enabled = true;
    private Integer priority = 100;
}
