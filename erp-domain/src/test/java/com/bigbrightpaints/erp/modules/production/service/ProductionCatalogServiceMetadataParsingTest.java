package com.bigbrightpaints.erp.modules.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProductionCatalogServiceMetadataParsingTest {

  private ProductionCatalogService service;

  @BeforeEach
  void setUp() {
    service =
        new ProductionCatalogService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
  }

  @Test
  void metadataLong_returnsNullWhenNumericStringOverflowsLong() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("supplierId", "92233720368547758070");

    Long parsed = ReflectionTestUtils.invokeMethod(service, "metadataLong", metadata, "supplierId");

    assertThat(parsed).isNull();
  }

  @Test
  void hasLongValue_returnsFalseWhenNumericStringOverflowsLong() {
    Boolean parsed =
        ReflectionTestUtils.invokeMethod(service, "hasLongValue", "92233720368547758070");

    assertThat(parsed).isFalse();
  }

  @Test
  void metadataLong_returnsPositiveLongWhenNumericStringFits() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("supplierId", "42");

    Long parsed = ReflectionTestUtils.invokeMethod(service, "metadataLong", metadata, "supplierId");

    assertThat(parsed).isEqualTo(42L);
  }

  @Test
  void metadataLong_returnsNullWhenNumericStringIsZero() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("supplierId", "0");

    Long parsed = ReflectionTestUtils.invokeMethod(service, "metadataLong", metadata, "supplierId");

    assertThat(parsed).isNull();
  }

  @Test
  void hasLongValue_returnsTrueForPositiveNumericString() {
    Boolean parsed = ReflectionTestUtils.invokeMethod(service, "hasLongValue", "42");

    assertThat(parsed).isTrue();
  }

  @Test
  void hasLongValue_returnsFalseForZeroNumericString() {
    Boolean parsed = ReflectionTestUtils.invokeMethod(service, "hasLongValue", "0");

    assertThat(parsed).isFalse();
  }
}
