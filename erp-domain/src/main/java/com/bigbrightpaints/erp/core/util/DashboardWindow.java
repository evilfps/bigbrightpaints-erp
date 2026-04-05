package com.bigbrightpaints.erp.core.util;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record DashboardWindow(
    LocalDate start,
    LocalDate end,
    LocalDate compareStart,
    LocalDate compareEnd,
    ZoneId zone,
    String bucket,
    int bucketDays) {
  private static final int DEFAULT_WINDOW_DAYS = 30;
  private static final Pattern DAY_WINDOW_PATTERN = Pattern.compile("^\\d+d$");

  public static DashboardWindow resolve(
      String window, String compare, String timezone, String fallbackTimezone) {
    ZoneId zone = parseZone(timezone, fallbackTimezone);
    LocalDate today = LocalDate.ofInstant(CompanyTime.now(), zone);
    WindowRange range = resolveRange(window, today);
    long days = ChronoUnit.DAYS.between(range.start(), range.end()) + 1;
    ComparisonRange comparisonRange = resolveComparisonRange(range, days, compare);
    int bucketDays = days <= 31 ? 1 : 7;
    String bucket = bucketDays == 1 ? "DAILY" : "WEEKLY";
    return new DashboardWindow(
        range.start(),
        range.end(),
        comparisonRange.start(),
        comparisonRange.end(),
        zone,
        bucket,
        bucketDays);
  }

  public Instant startInstant() {
    return start.atStartOfDay(zone).toInstant();
  }

  public Instant endExclusiveInstant() {
    return end.plusDays(1).atStartOfDay(zone).toInstant();
  }

  public List<LocalDate> bucketStarts() {
    List<LocalDate> buckets = new ArrayList<>();
    LocalDate cursor = start;
    while (!cursor.isAfter(end)) {
      buckets.add(cursor);
      cursor = cursor.plusDays(bucketDays);
    }
    return buckets;
  }

  private static ZoneId parseZone(String timezone, String fallbackTimezone) {
    ZoneId requestedZone = resolveZoneId(timezone);
    if (requestedZone != null) {
      return requestedZone;
    }
    ZoneId fallbackZone = resolveZoneId(fallbackTimezone);
    return fallbackZone != null ? fallbackZone : ZoneId.of("UTC");
  }

  private static WindowRange resolveRange(String window, LocalDate today) {
    if (window == null || window.isBlank()) {
      return rangeForDays(today, DEFAULT_WINDOW_DAYS);
    }
    String normalized = window.trim().toLowerCase(Locale.ROOT);
    if (DAY_WINDOW_PATTERN.matcher(normalized).matches()) {
      String daysPart = normalized.substring(0, normalized.length() - 1);
      WindowRange dayRange = resolveDayWindow(daysPart, today);
      if (dayRange != null) {
        return dayRange;
      }
    }
    return switch (normalized) {
      case "mtd" -> new WindowRange(today.withDayOfMonth(1), today);
      case "qtd" -> new WindowRange(startOfQuarter(today), today);
      case "ytd" -> new WindowRange(LocalDate.of(today.getYear(), 1, 1), today);
      default -> rangeForDays(today, DEFAULT_WINDOW_DAYS);
    };
  }

  private static ZoneId resolveZoneId(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return null;
    }
    try {
      return ZoneId.of(candidate.trim());
    } catch (DateTimeException ex) {
      return null;
    }
  }

  private static WindowRange resolveDayWindow(String daysPart, LocalDate today) {
    try {
      return rangeForDays(today, Long.parseLong(daysPart));
    } catch (DateTimeException | NumberFormatException ex) {
      return null;
    }
  }

  private static WindowRange rangeForDays(LocalDate today, long days) {
    long safeDays = Math.max(days, 1L);
    LocalDate start = today.minusDays(safeDays - 1L);
    return new WindowRange(start, today);
  }

  private static LocalDate startOfQuarter(LocalDate today) {
    int month = today.getMonthValue();
    int quarterStartMonth = ((month - 1) / 3) * 3 + 1;
    return LocalDate.of(today.getYear(), quarterStartMonth, 1);
  }

  private static ComparisonRange resolveComparisonRange(
      WindowRange range, long days, String compare) {
    if (compare == null || compare.isBlank()) {
      return ComparisonRange.empty();
    }
    return switch (compare.trim().toLowerCase(Locale.ROOT)) {
      case "prev" -> resolvePreviousComparisonRange(range, days);
      case "yoy" -> resolveYearOverYearComparisonRange(range);
      default -> ComparisonRange.empty();
    };
  }

  private static ComparisonRange resolvePreviousComparisonRange(WindowRange range, long days) {
    long availablePriorDays = ChronoUnit.DAYS.between(LocalDate.MIN, range.start());
    if (availablePriorDays < days) {
      return ComparisonRange.empty();
    }
    LocalDate compareEnd = range.start().minusDays(1);
    LocalDate compareStart = compareEnd.minusDays(days - 1L);
    return new ComparisonRange(compareStart, compareEnd);
  }

  private static ComparisonRange resolveYearOverYearComparisonRange(WindowRange range) {
    if (!canShiftBackOneYear(range.start()) || !canShiftBackOneYear(range.end())) {
      return ComparisonRange.empty();
    }
    return new ComparisonRange(range.start().minusYears(1), range.end().minusYears(1));
  }

  private static boolean canShiftBackOneYear(LocalDate date) {
    return date.getYear() > LocalDate.MIN.getYear();
  }

  private record WindowRange(LocalDate start, LocalDate end) {}

  private record ComparisonRange(LocalDate start, LocalDate end) {
    private static ComparisonRange empty() {
      return new ComparisonRange(null, null);
    }
  }
}
