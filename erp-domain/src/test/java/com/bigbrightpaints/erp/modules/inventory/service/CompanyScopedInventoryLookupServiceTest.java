package com.bigbrightpaints.erp.modules.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class CompanyScopedInventoryLookupServiceTest {

  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private ProductionProductRepository productionProductRepository;

  private CompanyScopedInventoryLookupService lookupService;
  private Company company;

  @BeforeEach
  void setUp() {
    lookupService =
        new CompanyScopedInventoryLookupService(
            rawMaterialRepository, finishedGoodRepository, productionProductRepository);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 44L);
    company.setCode("BBP");
    company.setTimezone("UTC");
  }

  @Test
  void requireActiveRawMaterial_returnsMaterialWhenLinkedProductIsActive() {
    RawMaterial material = rawMaterial(10L, "RM-BBP-TIO2");
    ProductionProduct product = productionProduct(88L, true);
    when(rawMaterialRepository.findByCompanyAndId(company, 10L)).thenReturn(Optional.of(material));
    when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "RM-BBP-TIO2"))
        .thenReturn(Optional.of(product));

    RawMaterial resolved = lookupService.requireActiveRawMaterial(company, 10L);

    assertThat(resolved).isSameAs(material);
  }

  @Test
  void lockActiveRawMaterial_skipsCatalogLookupWhenSkuIsBlank() {
    RawMaterial material = rawMaterial(11L, "   ");
    when(rawMaterialRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(material));

    RawMaterial resolved = lookupService.lockActiveRawMaterial(company, 11L);

    assertThat(resolved).isSameAs(material);
    verifyNoInteractions(productionProductRepository);
  }

  @Test
  void requireActiveRawMaterial_rejectsInactiveLinkedProduct() {
    RawMaterial material = rawMaterial(12L, "RM-BBP-ZINC");
    ProductionProduct product = productionProduct(89L, false);
    when(rawMaterialRepository.findByCompanyAndId(company, 12L)).thenReturn(Optional.of(material));
    when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "RM-BBP-ZINC"))
        .thenReturn(Optional.of(product));

    assertThatThrownBy(() -> lookupService.requireActiveRawMaterial(company, 12L))
        .isInstanceOf(ApplicationException.class)
        .matches(
            ex -> ((ApplicationException) ex).getErrorCode() == ErrorCode.BUSINESS_INVALID_STATE)
        .hasMessageContaining("Catalog item is inactive for raw material RM-BBP-ZINC");
  }

  @Test
  void lockActiveRawMaterial_rejectsMissingOrWrongTenantMaterial() {
    when(rawMaterialRepository.lockByCompanyAndId(company, 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> lookupService.lockActiveRawMaterial(company, 99L))
        .isInstanceOf(ApplicationException.class)
        .matches(
            ex -> ((ApplicationException) ex).getErrorCode() == ErrorCode.VALIDATION_INVALID_REFERENCE)
        .hasMessageContaining("Raw material not found");
  }

  @Test
  void requireActiveFinishedGood_returnsFinishedGoodWhenCatalogEntryIsMissing() {
    FinishedGood finishedGood = finishedGood(21L, "FG-BBP-PRIMER");
    when(finishedGoodRepository.findByCompanyAndId(company, 21L))
        .thenReturn(Optional.of(finishedGood));
    when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "FG-BBP-PRIMER"))
        .thenReturn(Optional.empty());

    FinishedGood resolved = lookupService.requireActiveFinishedGood(company, 21L);

    assertThat(resolved).isSameAs(finishedGood);
  }

  @Test
  void requireActiveRawMaterial_skipsCatalogLookupWhenCompanyIsNull() {
    RawMaterial material = rawMaterial(13L, "RM-NO-COMPANY");
    when(rawMaterialRepository.findByCompanyAndId(null, 13L)).thenReturn(Optional.of(material));

    RawMaterial resolved = lookupService.requireActiveRawMaterial(null, 13L);

    assertThat(resolved).isSameAs(material);
    verifyNoInteractions(productionProductRepository);
  }

  @Test
  void lockActiveFinishedGood_returnsFinishedGoodWhenLinkedProductIsActive() {
    FinishedGood finishedGood = finishedGood(23L, "FG-BBP-SATIN");
    ProductionProduct product = productionProduct(91L, true);
    when(finishedGoodRepository.lockByCompanyAndId(company, 23L))
        .thenReturn(Optional.of(finishedGood));
    when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "FG-BBP-SATIN"))
        .thenReturn(Optional.of(product));

    FinishedGood resolved = lookupService.lockActiveFinishedGood(company, 23L);

    assertThat(resolved).isSameAs(finishedGood);
  }

  @Test
  void lockActiveFinishedGood_rejectsInactiveLinkedProduct() {
    FinishedGood finishedGood = finishedGood(22L, "FG-BBP-EMULSION");
    ProductionProduct product = productionProduct(90L, false);
    when(finishedGoodRepository.lockByCompanyAndId(company, 22L))
        .thenReturn(Optional.of(finishedGood));
    when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "FG-BBP-EMULSION"))
        .thenReturn(Optional.of(product));

    assertThatThrownBy(() -> lookupService.lockActiveFinishedGood(company, 22L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Catalog item is inactive for finished good FG-BBP-EMULSION");

    verify(finishedGoodRepository).lockByCompanyAndId(company, 22L);
  }

  @Test
  void lockActiveFinishedGood_rejectsMissingOrWrongTenantFinishedGood() {
    when(finishedGoodRepository.lockByCompanyAndId(company, 404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> lookupService.lockActiveFinishedGood(company, 404L))
        .isInstanceOf(ApplicationException.class)
        .matches(
            ex -> ((ApplicationException) ex).getErrorCode() == ErrorCode.VALIDATION_INVALID_REFERENCE)
        .hasMessageContaining("Finished good not found");
  }

  private RawMaterial rawMaterial(Long id, String sku) {
    RawMaterial material = new RawMaterial();
    ReflectionTestUtils.setField(material, "id", id);
    material.setSku(sku);
    return material;
  }

  private FinishedGood finishedGood(Long id, String productCode) {
    FinishedGood finishedGood = new FinishedGood();
    ReflectionTestUtils.setField(finishedGood, "id", id);
    finishedGood.setProductCode(productCode);
    return finishedGood;
  }

  private ProductionProduct productionProduct(Long id, boolean active) {
    ProductionProduct product = new ProductionProduct();
    ReflectionTestUtils.setField(product, "id", id);
    product.setActive(active);
    return product;
  }
}
