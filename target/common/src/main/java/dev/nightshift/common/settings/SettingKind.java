package dev.nightshift.common.settings;

/** Runtime config values and feature flags are stored the same way but listed separately. */
public enum SettingKind {
    CONFIG, FLAG;

    public String path() {
        return this == CONFIG ? "config" : "flags";
    }
}
