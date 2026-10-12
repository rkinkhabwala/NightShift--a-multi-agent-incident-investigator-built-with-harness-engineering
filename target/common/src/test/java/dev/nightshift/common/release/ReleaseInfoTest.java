package dev.nightshift.common.release;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseInfoTest {

    @Test
    void comparesDottedVersionsNumerically() {
        assertThat(ReleaseInfo.compare("1.4", "1.3")).isPositive();
        assertThat(ReleaseInfo.compare("1.10", "1.4")).isPositive();
        assertThat(ReleaseInfo.compare("2.2", "2.2.0")).isZero();
        assertThat(ReleaseInfo.compare("2.3", "2.4")).isNegative();
    }

    @Test
    void isAtLeastUsesTheRunningVersion() {
        ReleaseInfo v14 = new ReleaseInfo("payments", "1.4");
        assertThat(v14.isAtLeast("1.4")).isTrue();
        assertThat(v14.isAtLeast("1.3")).isTrue();
        assertThat(new ReleaseInfo("payments", "1.3").isAtLeast("1.4")).isFalse();
    }
}
