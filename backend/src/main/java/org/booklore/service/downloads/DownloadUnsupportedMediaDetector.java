package org.booklore.service.downloads;

import java.util.regex.Pattern;

public final class DownloadUnsupportedMediaDetector {

    private static final Pattern UNSUPPORTED_MEDIA_MARKER = Pattern.compile(
            "(?i)(?:"
                    + "\\bmp4\\b|\\bmkv\\b|\\bavi\\b|\\bmov\\b|\\bwmv\\b|\\bflac\\b|\\bmp3\\b|\\baac\\b|\\bopus\\b|"
                    + "\\b480p\\b|\\b720p\\b|\\b1080p\\b|\\b2160p\\b|\\bfullhd\\b|\\bbdrip\\b|\\bwebrip\\b|\\bhdtv\\b|\\bbluray\\b|\\bblu ray\\b|"
                    + "\\bx264\\b|\\bx265\\b|\\bhevc\\b|\\bh\\s?264\\b|\\bh\\s?265\\b|\\b10bit\\b|\\bdual audio\\b|\\bsubbed\\b|\\bsoftsubs?\\b|"
                    + "\\bvostfr\\b|\\bsub ita\\b|\\bsub esp\\b|\\bsoundtrack\\b|\\bost\\b|\\bs\\d{1,2}\\s?e\\d{1,3}\\b|\\btv anime\\b|\\bmovies other\\b|"
                    + "\\bfitgirl\\b|\\bdodi\\b|\\belamigos\\b|\\bsteamrip\\b|\\bskidrow\\b|\\breloaded\\b|\\bplaza\\b|\\brazor1911\\b|\\bcodex\\b|\\bgame repack\\b|"
                    + "\\bultimate\\s+ninja\\s+storm\\b|\\bshinobi\\s+striker\\b|\\bvideo\\s*game\\b|\\bpc\\s*game\\b|\\bdlcs?\\b|\\bps[345]\\b|\\bcusa\\d+\\b|\\bpkg\\b|\\bnsp\\b|\\bxci\\b|"
                    + "\\bxxx\\b|\\bporn(?:o|ography)?\\b|\\bjav\\b|\\badult toys?\\b|\\berotic\\b|\\bnaked\\b|\\bundress\\b|\\bmasturbation\\b|"
                    + "\\btits?\\b|\\bbreasts?\\b|\\bhard\\s+ass\\b|\\bhot\\s+ass\\b|\\badult\\s+video\\b|\\bsex\\s+video\\b"
                    + ")"
    );

    private DownloadUnsupportedMediaDetector() {
    }

    public static boolean matches(String evidence) {
        return evidence != null && UNSUPPORTED_MEDIA_MARKER.matcher(evidence).find();
    }
}
