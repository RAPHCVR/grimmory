package org.booklore.model.enums;

public enum DownloadContentKind {
    AUTO,
    BOOK,
    MANGA,
    COMIC,
    WEBTOON;

    public boolean isAuto() {
        return this == AUTO;
    }

    public boolean isSequentialArt() {
        return this == MANGA || this == COMIC || this == WEBTOON;
    }
}
