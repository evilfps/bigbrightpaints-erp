package com.bigbrightpaints.erp.modules.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class CompanyScopedPurchasingLookupServiceTest {

  @Mock private SupplierRepository supplierRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  private CompanyScopedPurchasingLookupService lookupService;
  private Company company;

  @BeforeEach
  void setUp() {
    lookupService =
        new CompanyScopedPurchasingLookupService(supplierRepository, rawMaterialPurchaseRepository);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 44L);
    company.setCode("BBP");
    company.setTimezone("UTC");
  }

  @Test
  void requireSupplier_returnsSupplierWhenPresent() {
    Supplier supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 77L);
    when(supplierRepository.findByCompanyAndId(company, 77L)).thenReturn(Optional.of(supplier));

    assertThat(lookupService.requireSupplier(company, 77L)).isSameAs(supplier);
  }

  @Test
  void requireSupplier_rejectsMissingSupplier() {
    when(supplierRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> lookupService.requireSupplier(company, 99L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Supplier not found: id=99");
  }

  @Test
  void requireRawMaterialPurchase_returnsPurchaseWhenPresent() {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    ReflectionTestUtils.setField(purchase, "id", 88L);
    when(rawMaterialPurchaseRepository.findByCompanyAndId(company, 88L))
        .thenReturn(Optional.of(purchase));

    assertThat(lookupService.requireRawMaterialPurchase(company, 88L)).isSameAs(purchase);
  }

  @Test
  void requireRawMaterialPurchase_rejectsMissingPurchase() {
    when(rawMaterialPurchaseRepository.findByCompanyAndId(company, 123L))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> lookupService.requireRawMaterialPurchase(company, 123L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Raw material purchase not found: id=123");
  }
}
