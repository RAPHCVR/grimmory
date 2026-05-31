package org.booklore.model.enums;

public enum DownloadSequenceNumberType {
    AUTO,
    VOLUME,
    ISSUE,
    CHAPTER,
    EPISODE;

    public boolean isAuto() {
        return this == AUTO;
    }

    public boolean isVolumeLike() {
        return this == VOLUME || this == ISSUE;
    }

    public boolean isChapterLike() {
        return this == CHAPTER || this == EPISODE;
    }
}
