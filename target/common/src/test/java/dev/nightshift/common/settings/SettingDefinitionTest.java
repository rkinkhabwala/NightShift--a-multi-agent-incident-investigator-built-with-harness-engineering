package dev.nightshift.common.settings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingDefinitionTest {

    private final SettingDefinition pool = SettingDefinition.intConfig("orders.db.pool.max", 10, 1, 50, "");
    private final SettingDefinition flag = SettingDefinition.flag("new_pricing", false, "");

    @Test
    void acceptsAndNormalisesValidValues() {
        assertThat(pool.validate(" 1 ")).isEqualTo("1");
        assertThat(flag.validate("TRUE")).isEqualTo("true");
    }

    @Test
    void rejectsOutOfRangeAndMalformedValues() {
        assertThatThrownBy(() -> pool.validate("0")).hasMessageContaining("outside");
        assertThatThrownBy(() -> pool.validate("ten")).hasMessageContaining("integer");
        assertThatThrownBy(() -> flag.validate("yes")).hasMessageContaining("true or false");
        assertThatThrownBy(() -> flag.validate(null)).hasMessageContaining("required");
    }
}
