package dev.nightshift.common.settings;

/**
 * One runtime setting a service exposes. Integer settings may carry bounds; flags are booleans.
 */
public record SettingDefinition(SettingKind kind, String key, Type type, String defaultValue,
                                Long min, Long max, String description) {

    public enum Type { INT, BOOL, STRING }

    public static SettingDefinition intConfig(String key, long defaultValue, long min, long max, String description) {
        return new SettingDefinition(SettingKind.CONFIG, key, Type.INT, Long.toString(defaultValue), min, max, description);
    }

    public static SettingDefinition flag(String key, boolean defaultValue, String description) {
        return new SettingDefinition(SettingKind.FLAG, key, Type.BOOL, Boolean.toString(defaultValue), null, null, description);
    }

    /** Returns the normalised value, or throws if it is not valid for this setting. */
    public String validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException(key + ": value is required");
        }
        String v = value.trim();
        switch (type) {
            case INT -> {
                long n;
                try {
                    n = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + ": expected an integer, got '" + value + "'");
                }
                if ((min != null && n < min) || (max != null && n > max)) {
                    throw new IllegalArgumentException(key + ": " + n + " is outside [" + min + ", " + max + "]");
                }
                return Long.toString(n);
            }
            case BOOL -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(key + ": expected true or false, got '" + value + "'");
                }
                return v.toLowerCase();
            }
            default -> {
                return v;
            }
        }
    }
}
