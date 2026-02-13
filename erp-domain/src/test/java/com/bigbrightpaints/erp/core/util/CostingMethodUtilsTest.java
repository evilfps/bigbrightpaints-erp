package com.bigbrightpaints.erp.core.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CostingMethodUtilsTest {

    @Test
    void isWeightedAverage_acceptsSupportedAliases() {
        assertThat(CostingMethodUtils.isWeightedAverage("WAC")).isTrue();
        assertThat(CostingMethodUtils.isWeightedAverage("weighted_average")).isTrue();
        assertThat(CostingMethodUtils.isWeightedAverage("weighted-average")).isTrue();
        assertThat(CostingMethodUtils.isWeightedAverage("  wAc  ")).isTrue();
    }

    @Test
    void isWeightedAverage_rejectsUnsupportedValues() {
        assertThat(CostingMethodUtils.isWeightedAverage(null)).isFalse();
        assertThat(CostingMethodUtils.isWeightedAverage("")).isFalse();
        assertThat(CostingMethodUtils.isWeightedAverage("   ")).isFalse();
        assertThat(CostingMethodUtils.isWeightedAverage("FIFO")).isFalse();
        assertThat(CostingMethodUtils.isWeightedAverage("LIFO")).isFalse();
    }

    @Test
    void isWeightedAverage_isLocaleStable() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(CostingMethodUtils.isWeightedAverage("weighted_average")).isTrue();
        } finally {
            Locale.setDefault(previous);
        }
    }
}
