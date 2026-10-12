package dev.nightshift.common.settings;

import java.util.List;

/** Each service declares the config keys and feature flags it supports by providing this bean. */
public record SettingsCatalog(List<SettingDefinition> definitions) {

    public static SettingsCatalog of(SettingDefinition... definitions) {
        return new SettingsCatalog(List.of(definitions));
    }
}
