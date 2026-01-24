package com.bigbrightpaints.erp.modules.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class AccountingPeriodTest {

    @Test
    void prePersist_setsPublicId_whenMissing() {
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(2024);
        period.setMonth(1);

        period.prePersist();

        assertThat(period.getPublicId()).isNotNull();
    }

    @Test
    void prePersist_setsYearMonthFromStartDate_andNormalizesRange() {
        AccountingPeriod period = new AccountingPeriod();
        period.setStartDate(LocalDate.of(2024, 5, 15));

        period.prePersist();

        assertThat(period.getYear()).isEqualTo(2024);
        assertThat(period.getMonth()).isEqualTo(5);
        assertThat(period.getStartDate()).isEqualTo(LocalDate.of(2024, 5, 1));
        assertThat(period.getEndDate()).isEqualTo(LocalDate.of(2024, 5, 31));
    }

    @Test
    void prePersist_setsStartAndEndFromYearMonth() {
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(2024);
        period.setMonth(2);

        period.prePersist();

        YearMonth yearMonth = YearMonth.of(2024, 2);
        assertThat(period.getStartDate()).isEqualTo(yearMonth.atDay(1));
        assertThat(period.getEndDate()).isEqualTo(yearMonth.atEndOfMonth());
    }

    @Test
    void getLabel_formatsEnglishMonthName() {
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(2024);
        period.setMonth(1);

        assertThat(period.getLabel()).isEqualTo("January 2024");
    }

    @Test
    void contains_includesStartAndEndDates() {
        AccountingPeriod period = new AccountingPeriod();
        period.setStartDate(LocalDate.of(2024, 3, 1));
        period.setEndDate(LocalDate.of(2024, 3, 31));

        assertThat(period.contains(LocalDate.of(2024, 3, 1))).isTrue();
        assertThat(period.contains(LocalDate.of(2024, 3, 15))).isTrue();
        assertThat(period.contains(LocalDate.of(2024, 3, 31))).isTrue();
    }

    @Test
    void contains_returnsFalseForNullOrOutsideDates() {
        AccountingPeriod period = new AccountingPeriod();
        period.setStartDate(LocalDate.of(2024, 3, 1));
        period.setEndDate(LocalDate.of(2024, 3, 31));

        assertThat(period.contains(null)).isFalse();
        assertThat(period.contains(LocalDate.of(2024, 2, 29))).isFalse();
        assertThat(period.contains(LocalDate.of(2024, 4, 1))).isFalse();
    }
}
