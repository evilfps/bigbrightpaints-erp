package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared package-size parser used across factory packing flows.
 */
final class PackagingSizeParser {

  private static final BigDecimal ONE_THOUSAND = new BigDecimal("1000");
  private static final Pattern BARE_SIZE_PATTERN = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?$");
  private static final Pattern SIZE_WITH_UNIT_PATTERN =
      Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*(ML|L|LTR|LITRE|LITER)$");

  private PackagingSizeParser() {}

  static BigDecimal parseSizeInLiters(String label) {
    return parseSizeInLiters(label, false);
  }

  static BigDecimal parseSizeInLitersAllowBareNumber(String label) {
    return parseSizeInLiters(label, true);
  }

  private static BigDecimal parseSizeInLiters(String label, boolean allowBareNumber) {
    if (label == null || label.isBlank()) {
      return null;
    }
    String normalized = label.trim().toUpperCase(Locale.ROOT);
    if (allowBareNumber && BARE_SIZE_PATTERN.matcher(normalized).matches()) {
      return new BigDecimal(normalized);
    }

    Matcher matcher = SIZE_WITH_UNIT_PATTERN.matcher(normalized);
    if (!matcher.matches()) {
      return null;
    }
    BigDecimal value;
    try {
      value = new BigDecimal(matcher.group(1));
    } catch (NumberFormatException ex) {
      return null;
    }
    String unit = matcher.group(2);
    if ("ML".equals(unit)) {
      return value.divide(ONE_THOUSAND, 6, RoundingMode.HALF_UP);
    }
    return value;
  }
}
