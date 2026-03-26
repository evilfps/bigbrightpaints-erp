package com.bigbrightpaints.erp.modules.company.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompanyLifecycleStateConverterTest {

  private final CompanyLifecycleStateConverter converter = new CompanyLifecycleStateConverter();

  @Test
  void convertToDatabaseColumn_preserves_schemaCompatibleLifecycleValues() {
    assertThat(converter.convertToDatabaseColumn(CompanyLifecycleState.ACTIVE)).isEqualTo("ACTIVE");
    assertThat(converter.convertToDatabaseColumn(CompanyLifecycleState.SUSPENDED))
        .isEqualTo("SUSPENDED");
    assertThat(converter.convertToDatabaseColumn(CompanyLifecycleState.DEACTIVATED))
        .isEqualTo("DEACTIVATED");
  }

  @Test
  void convertToEntityAttribute_failsClosed_forUnknownStoredValue() {
    assertThat(converter.convertToEntityAttribute("ACTIVE"))
        .isEqualTo(CompanyLifecycleState.ACTIVE);
    assertThat(converter.convertToEntityAttribute("HOLD"))
        .isEqualTo(CompanyLifecycleState.DEACTIVATED);
    assertThat(converter.convertToEntityAttribute("BLOCKED"))
        .isEqualTo(CompanyLifecycleState.DEACTIVATED);
    assertThat(converter.convertToEntityAttribute("mystery-state"))
        .isEqualTo(CompanyLifecycleState.DEACTIVATED);
    assertThat(converter.convertToEntityAttribute(null)).isEqualTo(CompanyLifecycleState.ACTIVE);
  }
}
