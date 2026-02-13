package com.bigbrightpaints.erp.core.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            assertThat(CostingMethodUtils.normalizeRawMaterialMethodOrDefault(" weighted_average ")).isEqualTo("WAC");
            assertThat(CostingMethodUtils.normalizeFinishedGoodMethodOrDefault(" weighted-average ")).isEqualTo("WAC");
            assertThat(CostingMethodUtils.canonicalizeFinishedGoodMethodForSync(" weighted_average ")).isEqualTo("WAC");
            assertThat(CostingMethodUtils.canonicalizeRawMaterialMethodForSync(" weighted-average ")).isEqualTo("WAC");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void normalizeRawMaterialMethodOrDefault_canonicalizesAliasesAndRejectsUnsupported() {
        assertThat(CostingMethodUtils.normalizeRawMaterialMethodOrDefault(null)).isEqualTo("FIFO");
        assertThat(CostingMethodUtils.normalizeRawMaterialMethodOrDefault(" weighted-average ")).isEqualTo("WAC");
        assertThat(CostingMethodUtils.normalizeRawMaterialMethodOrDefault("fifo")).isEqualTo("FIFO");

        assertThatThrownBy(() -> CostingMethodUtils.normalizeRawMaterialMethodOrDefault("LIFO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported costing method");
    }

    @Test
    void normalizeFinishedGoodMethodOrDefault_supportsLifoAndRejectsUnsupported() {
        assertThat(CostingMethodUtils.normalizeFinishedGoodMethodOrDefault(null)).isEqualTo("FIFO");
        assertThat(CostingMethodUtils.normalizeFinishedGoodMethodOrDefault(" weighted_average ")).isEqualTo("WAC");
        assertThat(CostingMethodUtils.normalizeFinishedGoodMethodOrDefault(" lifo ")).isEqualTo("LIFO");

        assertThatThrownBy(() -> CostingMethodUtils.normalizeFinishedGoodMethodOrDefault("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported costing method");
    }

    @Test
    void canonicalizeFinishedGoodMethodForSync_canonicalizesKnownAndPreservesUnknownTrimmed() {
        assertThat(CostingMethodUtils.canonicalizeFinishedGoodMethodForSync(" weighted_average ")).isEqualTo("WAC");
        assertThat(CostingMethodUtils.canonicalizeFinishedGoodMethodForSync(" lifo ")).isEqualTo("LIFO");
        assertThat(CostingMethodUtils.canonicalizeFinishedGoodMethodForSync(null)).isEqualTo("FIFO");
        assertThat(CostingMethodUtils.canonicalizeFinishedGoodMethodForSync(" custom_method ")).isEqualTo("custom_method");
    }

    @Test
    void canonicalizeRawMaterialMethodForSync_canonicalizesKnownAndPreservesUnknownTrimmed() {
        assertThat(CostingMethodUtils.canonicalizeRawMaterialMethodForSync(" weighted_average ")).isEqualTo("WAC");
        assertThat(CostingMethodUtils.canonicalizeRawMaterialMethodForSync(" fifo ")).isEqualTo("FIFO");
        assertThat(CostingMethodUtils.canonicalizeRawMaterialMethodForSync(null)).isEqualTo("FIFO");
        assertThat(CostingMethodUtils.canonicalizeRawMaterialMethodForSync(" custom_method ")).isEqualTo("custom_method");
    }

    @Test
    void resolveFinishedGoodBatchSelectionMethod_canonicalizesSupportedAndFallsBackToFifo() {
        assertThat(CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod("weighted-average"))
                .isEqualTo(CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC);
        assertThat(CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod("lifo"))
                .isEqualTo(CostingMethodUtils.FinishedGoodBatchSelectionMethod.LIFO);
        assertThat(CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod("fifo"))
                .isEqualTo(CostingMethodUtils.FinishedGoodBatchSelectionMethod.FIFO);
        assertThat(CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod("custom_method"))
                .isEqualTo(CostingMethodUtils.FinishedGoodBatchSelectionMethod.FIFO);
        assertThat(CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(null))
                .isEqualTo(CostingMethodUtils.FinishedGoodBatchSelectionMethod.FIFO);
    }

    @Test
    void resolveFinishedGoodBatchSelectionMethod_isLocaleStable() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod("weighted_average"))
                    .isEqualTo(CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC);
            assertThat(CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod("lifo"))
                    .isEqualTo(CostingMethodUtils.FinishedGoodBatchSelectionMethod.LIFO);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
