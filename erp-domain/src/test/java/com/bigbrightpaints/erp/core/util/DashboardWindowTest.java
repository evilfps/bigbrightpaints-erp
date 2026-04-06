package com.bigbrightpaints.erp.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class DashboardWindowTest {
  private static final long MAX_DASHBOARD_WINDOW_DAYS = 366L;

  @Test
  void resolve_defaultWindow_30Days() {
    DashboardWindow window = DashboardWindow.resolve(null, null, "UTC", null);
    int days = (int) ChronoUnit.DAYS.between(window.start(), window.end()) + 1;
    assertThat(days).isEqualTo(30);
    assertThat(window.bucketDays()).isEqualTo(1);
    assertThat(window.bucket()).isEqualTo("DAILY");
    assertThat(window.bucketStarts()).hasSize(days);
  }

  @Test
  void resolve_window7d_prevCompare() {
    DashboardWindow window = DashboardWindow.resolve("7d", "prev", "UTC", null);
    assertThat(window.compareEnd()).isEqualTo(window.start().minusDays(1));
    int compareDays = (int) ChronoUnit.DAYS.between(window.compareStart(), window.compareEnd()) + 1;
    assertThat(compareDays).isEqualTo(7);
  }

  @Test
  void resolve_window40d_weeklyBucket() {
    DashboardWindow window = DashboardWindow.resolve("40d", null, "UTC", null);
    assertThat(window.bucket()).isEqualTo("WEEKLY");
    assertThat(window.bucketDays()).isEqualTo(7);
  }

  @Test
  void resolve_mtd_startsAtFirstOfMonth() {
    DashboardWindow window = DashboardWindow.resolve("mtd", null, "UTC", null);
    assertThat(window.start().getDayOfMonth()).isEqualTo(1);
    assertThat(window.end()).isEqualTo(LocalDate.now(ZoneId.of("UTC")));
  }

  @Test
  void resolve_qtd_startsAtQuarter() {
    DashboardWindow window = DashboardWindow.resolve("qtd", null, "UTC", null);
    int month = window.start().getMonthValue();
    assertThat(month).isIn(1, 4, 7, 10);
    assertThat(window.start().getDayOfMonth()).isEqualTo(1);
  }

  @Test
  void resolve_ytd_startsAtJanFirst() {
    DashboardWindow window = DashboardWindow.resolve("ytd", null, "UTC", null);
    assertThat(window.start().getMonthValue()).isEqualTo(1);
    assertThat(window.start().getDayOfMonth()).isEqualTo(1);
  }

  @Test
  void resolve_compare_yoy_shiftsOneYear() {
    DashboardWindow window = DashboardWindow.resolve("10d", "yoy", "UTC", null);
    assertThat(window.compareStart()).isEqualTo(window.start().minusYears(1));
    assertThat(window.compareEnd()).isEqualTo(window.end().minusYears(1));
  }

  @Test
  void resolve_hugeParseableWindow_clampsToMaximumDays() {
    DashboardWindow window =
        DashboardWindow.resolve(minLocalDateWindowSpec("UTC"), null, "UTC", null);

    assertThat(windowLength(window)).isEqualTo(MAX_DASHBOARD_WINDOW_DAYS);
    assertThat(window.start()).isAfter(LocalDate.MIN);
  }

  @Test
  void resolve_comparePrevOversizedWindowClampsRangeBeforeComparison() {
    DashboardWindow window =
        DashboardWindow.resolve(minLocalDateWindowSpec("UTC"), "prev", "UTC", null);

    assertThat(windowLength(window)).isEqualTo(MAX_DASHBOARD_WINDOW_DAYS);
    assertThat(window.compareEnd()).isEqualTo(window.start().minusDays(1));
    assertThat(compareLength(window)).isEqualTo(MAX_DASHBOARD_WINDOW_DAYS);
  }

  @Test
  void resolve_compareYoyOversizedWindowClampsRangeBeforeComparison() {
    DashboardWindow window =
        DashboardWindow.resolve(minLocalDateWindowSpec("UTC"), "yoy", "UTC", null);

    assertThat(windowLength(window)).isEqualTo(MAX_DASHBOARD_WINDOW_DAYS);
    assertThat(window.compareStart()).isEqualTo(window.start().minusYears(1));
    assertThat(window.compareEnd()).isEqualTo(window.end().minusYears(1));
  }

  @Test
  void resolve_windowZeroDays_defaultsToOneDay() {
    DashboardWindow window = DashboardWindow.resolve("0d", null, "UTC", null);
    int days = (int) ChronoUnit.DAYS.between(window.start(), window.end()) + 1;
    assertThat(days).isEqualTo(1);
  }

  @Test
  void resolve_oversizedWindowDefaultsToThirtyDays() {
    DashboardWindow window =
        DashboardWindow.resolve("999999999999999999999999999d", null, "UTC", null);

    assertThat(windowLength(window)).isEqualTo(30);
  }

  @Test
  void resolve_malformedWindowDefaultsToThirtyDays() {
    DashboardWindow window = DashboardWindow.resolve("this-is-not-a-window", null, "UTC", null);

    assertThat(windowLength(window)).isEqualTo(30);
  }

  @Test
  void resolve_timezoneOffsetUsesRequestedZone() {
    DashboardWindow window = DashboardWindow.resolve("7d", null, "+05:30", "UTC");

    assertThat(window.zone()).isEqualTo(ZoneId.of("+05:30"));
  }

  @Test
  void resolve_gmtTimezoneOffsetUsesRequestedZone() {
    DashboardWindow window = DashboardWindow.resolve("7d", null, "GMT+05:30", "UTC");

    assertThat(window.zone()).isEqualTo(ZoneId.of("GMT+05:30"));
  }

  @Test
  void resolve_timezoneFallbackUsesFallback() {
    DashboardWindow window = DashboardWindow.resolve("7d", null, "Not/AZone", "UTC");
    assertThat(window.zone()).isEqualTo(ZoneId.of("UTC"));
  }

  private static long windowLength(DashboardWindow window) {
    return ChronoUnit.DAYS.between(window.start(), window.end()) + 1;
  }

  private static long compareLength(DashboardWindow window) {
    return ChronoUnit.DAYS.between(window.compareStart(), window.compareEnd()) + 1;
  }

  private static String minLocalDateWindowSpec(String timezone) {
    long days = ChronoUnit.DAYS.between(LocalDate.MIN, LocalDate.now(ZoneId.of(timezone))) + 1;
    return days + "d";
  }
}
