package com.bigbrightpaints.erp.modules.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

    @Test
    void setFxRate_rejectsNull() {
        JournalEntry entry = new JournalEntry();

        assertThatThrownBy(() -> entry.setFxRate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FX rate");
    }

    @Test
    void setFxRate_rejectsZero() {
        JournalEntry entry = new JournalEntry();

        assertThatThrownBy(() -> entry.setFxRate(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FX rate");
    }

    @Test
    void setFxRate_rejectsNegative() {
        JournalEntry entry = new JournalEntry();

        assertThatThrownBy(() -> entry.setFxRate(new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FX rate");
    }

    @Test
    void setFxRate_acceptsPositiveValue() {
        JournalEntry entry = new JournalEntry();

        assertThatCode(() -> entry.setFxRate(new BigDecimal("1.25"))).doesNotThrowAnyException();
        assertThat(entry.getFxRate()).isEqualByComparingTo("1.25");
    }

    @Test
    void prePersist_setsPostedCreatedAndUpdatedAtWhenPosted() {
        JournalEntry entry = new JournalEntry();
        entry.setStatus("POSTED");

        entry.prePersist();

        assertThat(entry.getCreatedAt()).isNotNull();
        assertThat(entry.getUpdatedAt()).isNotNull();
        assertThat(entry.getPostedAt()).isNotNull();
    }

    @Test
    void prePersist_doesNotSetPostedAtWhenDraft() {
        JournalEntry entry = new JournalEntry();
        entry.setStatus("DRAFT");

        entry.prePersist();

        assertThat(entry.getPostedAt()).isNull();
    }
}
