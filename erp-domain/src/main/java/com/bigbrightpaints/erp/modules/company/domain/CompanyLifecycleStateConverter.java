package com.bigbrightpaints.erp.modules.company.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class CompanyLifecycleStateConverter
    implements AttributeConverter<CompanyLifecycleState, String> {

  @Override
  public String convertToDatabaseColumn(CompanyLifecycleState attribute) {
    if (attribute == null) {
      return CompanyLifecycleState.ACTIVE.name();
    }
    return attribute.name();
  }

  @Override
  public CompanyLifecycleState convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) {
      return CompanyLifecycleState.ACTIVE;
    }
    return CompanyLifecycleState.fromStoredValue(dbData).orElse(CompanyLifecycleState.DEACTIVATED);
  }
}
