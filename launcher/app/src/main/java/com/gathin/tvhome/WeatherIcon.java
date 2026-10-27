package com.gathin.tvhome;

/** weather_now's icon. */
final class WeatherIcon {
    private WeatherIcon() {
    }

    /** A WMO weather code as one emoji. */
    static String of(int code, boolean day) {
        if (code == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";
        if (code <= 2) return day ? "\uD83C\uDF24\uFE0F" : "\u2601\uFE0F";
        if (code == 3) return "\u2601\uFE0F";
        if (code == 45 || code == 48) return "\uD83C\uDF2B\uFE0F";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\uD83C\uDF28\uFE0F";
        if (code >= 95) return "\u26C8\uFE0F";
        if (code >= 51) return "\uD83C\uDF27\uFE0F";
        return "\u2601\uFE0F";
    }
}
