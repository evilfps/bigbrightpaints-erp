package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.dto.AllowedSellableSizeDto;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@ExtendWith(MockitoExtension.class)
class PackingAllowedSizeServiceTest {

  @Mock private ProductionProductRepository productionProductRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private SizeVariantRepository sizeVariantRepository;

  @Test
  void listAllowedSellableSizes_usesProductFamilyTargetsWithFinishedGoods() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    UUID familyId = UUID.randomUUID();

    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", familyId);
    ProductionProduct oneLiter =
        product("FG-BASE-1L", "Primer White 1L", "Primer", "1L", familyId);
    ProductionProduct fourLiter =
        product("FG-BASE-4L", "Primer White 4L", "Primer", "4L", familyId);

    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, familyId))
        .thenReturn(List.of(fourLiter, oneLiter, base));
    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(
            List.of(
                finishedGood(501L, "FG-BASE-20L", "Primer White 20L"),
                finishedGood(101L, "FG-BASE-1L", "Primer White 1L"),
                finishedGood(401L, "FG-BASE-4L", "Primer White 4L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, oneLiter))
        .thenReturn(List.of(sizeVariant(oneLiter, "1L", BigDecimal.ONE, 12)));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, fourLiter))
        .thenReturn(List.of(sizeVariant(fourLiter, "4L", new BigDecimal("4"), 4)));

    List<AllowedSellableSizeDto> allowedSizes = service.listAllowedSellableSizes(company, log);

    assertThat(allowedSizes)
        .extracting(
            AllowedSellableSizeDto::sizeLabel, AllowedSellableSizeDto::childFinishedGoodId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("1L", 101L),
            org.assertj.core.groups.Tuple.tuple("4L", 401L),
            org.assertj.core.groups.Tuple.tuple("20L", 501L));
    assertThat(allowedSizes)
        .allSatisfy(
            allowed -> {
              assertThat(allowed.productFamilyName()).isEqualTo("Primer");
              assertThat(allowed.childSkuCode()).startsWith("FG-BASE-");
            });
  }

  @Test
  void resolveAllowedSellableSize_rejectsMissingTarget() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", null));

    assertThatThrownBy(() -> service.requireAllowedSellableSize(company, log, null, "20L", 1))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error ->
                assertThat(((ApplicationException) error).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT))
        .hasMessageContaining("Sellable size target is required for line 1");
  }

  @Test
  void resolveAllowedSellableSize_rejectsPackagingSizeMismatch() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", null);
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-20L", "Primer White 20L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));

    assertThatThrownBy(() -> service.requireAllowedSellableSize(company, log, 501L, "1L", 1))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error ->
                assertThat(((ApplicationException) error).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT))
        .hasMessageContaining("Packaging size '1L' does not match sellable size target '20L'");
  }

  private ProductionProduct product(
      String sku, String productName, String familyName, String sizeLabel, UUID variantGroupId) {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode(sku);
    product.setProductName(productName);
    product.setProductFamilyName(familyName);
    product.setSizeLabel(sizeLabel);
    product.setVariantGroupId(variantGroupId);
    product.setActive(true);
    product.setCartonSizes(Map.of(sizeLabel, 1));
    return product;
  }

  private FinishedGood finishedGood(Long id, String sku, String name) {
    FinishedGood finishedGood = new FinishedGood();
    org.springframework.test.util.ReflectionTestUtils.setField(finishedGood, "id", id);
    finishedGood.setProductCode(sku);
    finishedGood.setName(name);
    return finishedGood;
  }

  private SizeVariant sizeVariant(
      ProductionProduct product, String sizeLabel, BigDecimal litersPerUnit, int cartonQuantity) {
    SizeVariant sizeVariant = new SizeVariant();
    org.springframework.test.util.ReflectionTestUtils.setField(
        sizeVariant, "id", Math.abs((long) sizeLabel.hashCode()));
    sizeVariant.setProduct(product);
    sizeVariant.setSizeLabel(sizeLabel);
    sizeVariant.setLitersPerUnit(litersPerUnit);
    sizeVariant.setCartonQuantity(cartonQuantity);
    sizeVariant.setActive(true);
    return sizeVariant;
  }
}
