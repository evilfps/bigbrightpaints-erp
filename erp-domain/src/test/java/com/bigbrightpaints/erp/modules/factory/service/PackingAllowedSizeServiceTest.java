package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class PackingAllowedSizeServiceTest {

  @Mock private ProductionProductRepository productionProductRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private SizeVariantRepository sizeVariantRepository;

  @Test
  void listAllowedSellableSizes_returnsEmptyWhenCompanyOrBaseProductMissing() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    ProductionLog log = new ProductionLog();

    assertThat(service.listAllowedSellableSizes(null, log)).isEmpty();
    assertThat(service.listAllowedSellableSizes(new Company(), null)).isEmpty();

    Company company = new Company();
    log.setCompany(company);
    assertThat(service.listAllowedSellableSizes(company, log)).isEmpty();

    verifyNoInteractions(
        productionProductRepository, finishedGoodRepository, sizeVariantRepository);
  }

  @Test
  void listAllowedSellableSizes_usesProductFamilyTargetsWithFinishedGoods() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    UUID familyId = UUID.randomUUID();

    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", familyId);
    ProductionProduct oneLiter = product("FG-BASE-1L", "Primer White 1L", "Primer", "1L", familyId);
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
        .extracting(AllowedSellableSizeDto::sizeLabel, AllowedSellableSizeDto::childFinishedGoodId)
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
  void listAllowedSellableSizes_ignoresInactiveBlankOrUnresolvedFamilyMembers() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    UUID familyId = UUID.randomUUID();

    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", familyId);
    ProductionProduct inactive = product("FG-INACTIVE", "Inactive", "Primer", "2L", familyId);
    inactive.setActive(false);
    ProductionProduct blankSku = product("   ", "Blank SKU", "Primer", "3L", familyId);
    ProductionProduct missingFinishedGood =
        product("FG-MISSING", "Missing Finished Good", "Primer", "4L", familyId);
    ProductionProduct missingFinishedGoodId =
        product("FG-NO-ID", "Missing Finished Good Id", "Primer", "5L", familyId);
    ProductionProduct missingVariant =
        product("FG-NO-VARIANT", "Missing Variant", "Primer", "6L", familyId);
    ProductionProduct valid = product("FG-VALID", "Valid", "Primer", "1L", familyId);

    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, familyId))
        .thenReturn(
            Arrays.asList(
                inactive,
                blankSku,
                missingFinishedGood,
                missingFinishedGoodId,
                missingVariant,
                valid));
    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(
            List.of(
                finishedGood(303L, "   ", "Blank Product Code"),
                finishedGoodWithoutId("FG-NO-ID", "Missing Finished Good Id"),
                finishedGood(202L, "FG-NO-VARIANT", "Missing Variant"),
                finishedGood(101L, "FG-VALID", "Valid")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, missingVariant))
        .thenReturn(List.of());
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, valid))
        .thenReturn(List.of(sizeVariant(valid, "1L", BigDecimal.ONE, 12)));

    assertThat(service.listAllowedSellableSizes(company, log))
        .singleElement()
        .satisfies(
            allowed -> {
              assertThat(allowed.childFinishedGoodId()).isEqualTo(101L);
              assertThat(allowed.childSkuCode()).isEqualTo("FG-VALID");
              assertThat(allowed.sizeLabel()).isEqualTo("1L");
            });
  }

  @Test
  void listAllowedSellableSizes_fallsBackToBaseProductWhenVariantGroupQueryIsEmpty() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    UUID familyId = UUID.randomUUID();
    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", familyId);
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, familyId))
        .thenReturn(List.of());
    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-20L", "Primer White 20L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));

    assertThat(service.listAllowedSellableSizes(company, log))
        .singleElement()
        .extracting(AllowedSellableSizeDto::childFinishedGoodId, AllowedSellableSizeDto::sizeLabel)
        .containsExactly(501L, "20L");
  }

  @Test
  void resolveFamilyProducts_fallsBackToBaseProductWhenRepositoryReturnsNull() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    UUID familyId = UUID.randomUUID();
    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", familyId);

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, familyId))
        .thenReturn(null);

    @SuppressWarnings("unchecked")
    List<ProductionProduct> familyProducts =
        ReflectionTestUtils.invokeMethod(service, "resolveFamilyProducts", company, base);

    assertThat(familyProducts).containsExactly(base);
  }

  @Test
  void loadFinishedGoodsBySku_returnsEmptyWhenAllCodesBlank() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();

    @SuppressWarnings("unchecked")
    Map<String, FinishedGood> finishedGoodsBySku =
        ReflectionTestUtils.invokeMethod(
            service, "loadFinishedGoodsBySku", company, List.of(" ", "\t"));

    assertThat(finishedGoodsBySku).isEmpty();
  }

  @Test
  void listAllowedSellableSizes_returnsEmptyWhenProductSizeDoesNotMatchActiveVariants() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", null);
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    SizeVariant activeOtherSize = sizeVariant(base, "10L", new BigDecimal("10"), 1);
    SizeVariant inactiveMatchingSize = sizeVariant(base, "20L", new BigDecimal("20"), 1);
    inactiveMatchingSize.setActive(false);

    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-20L", "Primer White 20L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(activeOtherSize, inactiveMatchingSize));

    assertThat(service.listAllowedSellableSizes(company, log)).isEmpty();
  }

  @Test
  void listAllowedSellableSizes_returnsEmptyWhenMultipleActiveVariantsExistWithoutProductSize() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionProduct base =
        product("FG-BASE-MULTI", "Primer White", "Primer", null, UUID.randomUUID());
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, base.getVariantGroupId()))
        .thenReturn(List.of(base));
    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-MULTI", "Primer White")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(
            List.of(
                sizeVariant(base, "1L", BigDecimal.ONE, 12),
                sizeVariant(base, "4L", new BigDecimal("4"), 4)));

    assertThat(service.listAllowedSellableSizes(company, log)).isEmpty();
  }

  @Test
  void listAllowedSellableSizes_skipsVariantWithoutIdentifier() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionProduct base = product("FG-BASE-7L", "Primer White 7L", "Primer", "7L", null);
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(701L, "FG-BASE-7L", "Primer White 7L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariantWithoutId(base, "7L", new BigDecimal("7"), 1)));

    assertThat(service.listAllowedSellableSizes(company, log)).isEmpty();
  }

  @Test
  void resolveProductFamilyName_returnsNullForNullProduct() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    String familyName =
        invokePrivateString(service, "resolveProductFamilyName", ProductionProduct.class, null);

    assertThat(familyName).isNull();
  }

  @Test
  void normalizeSize_returnsEmptyStringForNull() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    String normalizedSize = invokePrivateString(service, "normalizeSize", String.class, null);

    assertThat(normalizedSize).isEmpty();
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
  void requireAllowedSellableSize_acceptsTrimmedCaseInsensitivePackagingSize() {
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

    PackingAllowedSizeService.AllowedSellableSizeTarget result =
        service.requireAllowedSellableSize(company, log, 501L, " 20l ", 1);

    assertThat(result.finishedGood().getId()).isEqualTo(501L);
    assertThat(result.sizeVariant().getSizeLabel()).isEqualTo("20L");
    assertThat(result.productFamilyName()).isEqualTo("Primer");
  }

  @Test
  void requireAllowedSellableSize_reusesPreloadedTargetsAcrossLines() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    UUID familyId = UUID.randomUUID();
    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", familyId);
    ProductionProduct child = product("FG-BASE-4L", "Primer White 4L", "Primer", "4L", familyId);
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, familyId))
        .thenReturn(List.of(base, child));
    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(
            List.of(
                finishedGood(501L, "FG-BASE-20L", "Primer White 20L"),
                finishedGood(401L, "FG-BASE-4L", "Primer White 4L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, child))
        .thenReturn(List.of(sizeVariant(child, "4L", new BigDecimal("4"), 4)));

    List<PackingAllowedSizeService.AllowedSellableSizeTarget> allowedTargets =
        service.resolveAllowedSellableSizeTargets(company, log);

    assertThat(
            service
                .requireAllowedSellableSize(allowedTargets, log, 501L, "20L", 1)
                .finishedGood()
                .getId())
        .isEqualTo(501L);
    assertThat(
            service
                .requireAllowedSellableSize(allowedTargets, log, 401L, "4L", 2)
                .finishedGood()
                .getId())
        .isEqualTo(401L);

    verify(productionProductRepository, times(1))
        .findByCompanyAndVariantGroupIdOrderByProductNameAsc(company, familyId);
    verify(finishedGoodRepository, times(1))
        .findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection());
    verify(sizeVariantRepository, times(1))
        .findByCompanyAndProductOrderBySizeLabelAsc(company, base);
    verify(sizeVariantRepository, times(1))
        .findByCompanyAndProductOrderBySizeLabelAsc(company, child);
  }

  @Test
  void requireAllowedSellableSize_rejectsChildTargetWhenVariantGroupIsMissing() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", null);
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);
    log.setProductionCode("PROD-NULL-GROUP");
    ReflectionTestUtils.setField(log, "id", 88L);

    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-20L", "Primer White 20L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));

    assertThatThrownBy(() -> service.requireAllowedSellableSize(company, log, 999L, "20L", 1))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error -> {
              ApplicationException ex = (ApplicationException) error;
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE);
              assertThat(ex.getDetails())
                  .containsEntry("productionLogId", 88L)
                  .containsEntry("childFinishedGoodId", 999L);
            })
        .hasMessageContaining(
            "Sellable size target is not allowed for production batch PROD-NULL-GROUP");
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

  @Test
  void listAllowedSellableSizes_fallsBackToSingleActiveVariantAndProductName() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionProduct base =
        product("FG-BASE-20L", "Primer White 20L", null, null, UUID.randomUUID());
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, base.getVariantGroupId()))
        .thenReturn(List.of(base));
    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-20L", "Primer White 20L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));

    AllowedSellableSizeDto allowed = service.listAllowedSellableSizes(company, log).getFirst();

    assertThat(allowed.childFinishedGoodId()).isEqualTo(501L);
    assertThat(allowed.childSkuCode()).isEqualTo("FG-BASE-20L");
    assertThat(allowed.childFinishedGoodName()).isEqualTo("Primer White 20L");
    assertThat(allowed.sizeVariantId()).isNotNull();
    assertThat(allowed.sizeLabel()).isEqualTo("20L");
    assertThat(allowed.piecesPerBox()).isEqualTo(1);
    assertThat(allowed.litersPerUnit()).isEqualByComparingTo("20");
    assertThat(allowed.productFamilyName()).isEqualTo("Primer White 20L");
  }

  @Test
  void listAllowedSellableSizes_fallsBackToSkuWhenNamesAreBlank() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    ProductionProduct base = product("FG-BASE-20L", " ", " ", null, null);
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setProduct(base);

    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-20L", "Primer White 20L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));

    assertThat(service.listAllowedSellableSizes(company, log))
        .singleElement()
        .extracting(AllowedSellableSizeDto::productFamilyName)
        .isEqualTo("FG-BASE-20L");
  }

  @Test
  void resolveAllowedSellableSize_rejectsUnknownTargetWithReferenceDetails() {
    PackingAllowedSizeService service =
        new PackingAllowedSizeService(
            productionProductRepository, finishedGoodRepository, sizeVariantRepository);

    Company company = new Company();
    UUID familyId = UUID.randomUUID();
    ProductionProduct base = product("FG-BASE-20L", "Primer White 20L", "Primer", "20L", familyId);
    ProductionLog log = new ProductionLog();
    org.springframework.test.util.ReflectionTestUtils.setField(log, "id", 77L);
    log.setCompany(company);
    log.setProduct(base);
    log.setProductionCode("PROD-001");

    when(productionProductRepository.findByCompanyAndVariantGroupIdOrderByProductNameAsc(
            company, familyId))
        .thenReturn(List.of());
    when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(
            org.mockito.ArgumentMatchers.eq(company), anyCollection()))
        .thenReturn(List.of(finishedGood(501L, "FG-BASE-20L", "Primer White 20L")));
    when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, base))
        .thenReturn(List.of(sizeVariant(base, "20L", new BigDecimal("20"), 1)));

    assertThatThrownBy(() -> service.requireAllowedSellableSize(company, log, 999L, "20L", 3))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error -> {
              ApplicationException ex = (ApplicationException) error;
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE);
              assertThat(ex.getDetails())
                  .containsEntry("productionLogId", 77L)
                  .containsEntry("childFinishedGoodId", 999L);
            })
        .hasMessageContaining("Sellable size target is not allowed for production batch PROD-001");
  }

  @Test
  void resolveAllowedSellableSize_rejectsMissingPackagingSize() {
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

    assertThatThrownBy(() -> service.requireAllowedSellableSize(company, log, 501L, "  ", 2))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error ->
                assertThat(((ApplicationException) error).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT))
        .hasMessageContaining("Packaging size is required for line 2");
  }

  @Test
  void allowedSellableSizeDto_exposesCanonicalFields() {
    AllowedSellableSizeDto dto =
        new AllowedSellableSizeDto(
            501L, "FG-BASE-20L", "Primer White 20L", 41L, "20L", 1, new BigDecimal("20"), "Primer");

    assertThat(dto.childFinishedGoodId()).isEqualTo(501L);
    assertThat(dto.childSkuCode()).isEqualTo("FG-BASE-20L");
    assertThat(dto.childFinishedGoodName()).isEqualTo("Primer White 20L");
    assertThat(dto.sizeVariantId()).isEqualTo(41L);
    assertThat(dto.sizeLabel()).isEqualTo("20L");
    assertThat(dto.piecesPerBox()).isEqualTo(1);
    assertThat(dto.litersPerUnit()).isEqualByComparingTo("20");
    assertThat(dto.productFamilyName()).isEqualTo("Primer");
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
    product.setCartonSizes(
        sizeLabel == null || sizeLabel.isBlank() ? Map.of() : Map.of(sizeLabel, 1));
    return product;
  }

  private FinishedGood finishedGood(Long id, String sku, String name) {
    FinishedGood finishedGood = new FinishedGood();
    if (id != null) {
      org.springframework.test.util.ReflectionTestUtils.setField(finishedGood, "id", id);
    }
    finishedGood.setProductCode(sku);
    finishedGood.setName(name);
    return finishedGood;
  }

  private FinishedGood finishedGoodWithoutId(String sku, String name) {
    return finishedGood(null, sku, name);
  }

  private SizeVariant sizeVariant(
      ProductionProduct product, String sizeLabel, BigDecimal litersPerUnit, int cartonQuantity) {
    SizeVariant sizeVariant = new SizeVariant();
    ReflectionTestUtils.setField(sizeVariant, "id", Math.abs((long) sizeLabel.hashCode()));
    sizeVariant.setProduct(product);
    sizeVariant.setSizeLabel(sizeLabel);
    sizeVariant.setLitersPerUnit(litersPerUnit);
    sizeVariant.setCartonQuantity(cartonQuantity);
    sizeVariant.setActive(true);
    return sizeVariant;
  }

  private SizeVariant sizeVariantWithoutId(
      ProductionProduct product, String sizeLabel, BigDecimal litersPerUnit, int cartonQuantity) {
    SizeVariant sizeVariant = new SizeVariant();
    sizeVariant.setProduct(product);
    sizeVariant.setSizeLabel(sizeLabel);
    sizeVariant.setLitersPerUnit(litersPerUnit);
    sizeVariant.setCartonQuantity(cartonQuantity);
    sizeVariant.setActive(true);
    return sizeVariant;
  }

  private String invokePrivateString(
      PackingAllowedSizeService service, String methodName, Class<?> parameterType, Object arg) {
    try {
      var method = PackingAllowedSizeService.class.getDeclaredMethod(methodName, parameterType);
      method.setAccessible(true);
      return (String) method.invoke(service, arg);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
