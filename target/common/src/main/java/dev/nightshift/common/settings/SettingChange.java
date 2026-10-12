package dev.nightshift.common.settings;

import java.time.Instant;

/** One entry of a setting's change history. Also published as a Spring event. */
public record SettingChange(long id, String service, SettingKind kind, String key, String oldValue,
                            String newValue, String changedBy, String reason, Instant changedAt) {
}
