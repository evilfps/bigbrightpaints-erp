package com.bigbrightpaints.erp.core.health;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@Service
public class ConfigurationHealthService {

  private static final Logger log = LoggerFactory.getLogger(ConfigurationHealthService.class);

  private static final List<String> RAW_MATERIAL_CATEGORIES =
      List.of("RAW_MATERIAL", "RAW MATERIAL", "RAW-MATERIAL");
  private static final List<String> REQUIRED_PRODUCT_METADATA_KEYS =
      List.of(
          "fgValuationAccountId",
          "fgCogsAccountId",
          "fgRevenueAccountId",
          "fgDiscountAccountId",
          "fgTaxAccountId",
          "wipAccountId",
          "semiFinishedAccountId");

  private final CompanyRepository companyRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final ProductionProductRepository productionProductRepository;

  public ConfigurationHealthService(
      CompanyRepository companyRepository,
      FinishedGoodRepository finishedGoodRepository,
      RawMaterialRepository rawMaterialRepository,
      ProductionProductRepository productionProductRepository) {
    this.companyRepository = companyRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.productionProductRepository = productionProductRepository;
  }

  public ConfigurationHealthReport evaluate() {
    List<ConfigurationIssue> issues = new ArrayList<>();
    companyRepository.findAll().forEach(company -> validateCompany(company, issues));
    return new ConfigurationHealthReport(issues.isEmpty(), List.copyOf(issues));
  }

  public ConfigurationHealthReport evaluateCompany(Company company) {
    List<ConfigurationIssue> issues = new ArrayList<>();
    if (company != null) {
      validateCompany(company, issues);
    }
    return new ConfigurationHealthReport(issues.isEmpty(), List.copyOf(issues));
  }

  public void assertHealthy() {
    ConfigurationHealthReport report = evaluate();
    if (!report.healthy()) {
      throw new IllegalStateException(buildFailureMessage(report));
    }
  }

  private void validateCompany(Company company, List<ConfigurationIssue> issues) {
    checkBaseCurrency(company, issues);
    checkDefaultAccounts(company, issues);
    checkTaxAccounts(company, issues);

    Map<String, FinishedGood> finishedGoodsBySku =
        finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).stream()
            .filter(fg -> StringUtils.hasText(fg.getProductCode()))
            .collect(
                Collectors.toMap(
                    fg -> fg.getProductCode().trim(),
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));

    Map<String, RawMaterial> rawMaterialsBySku =
        rawMaterialRepository.findByCompanyOrderByNameAsc(company).stream()
            .filter(material -> StringUtils.hasText(material.getSku()))
            .collect(
                Collectors.toMap(
                    material -> material.getSku().trim(),
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));

    rawMaterialsBySku
        .values()
        .forEach(
            material -> {
              if (material.getInventoryAccountId() == null) {
                issues.add(
                    issue(
                        company,
                        "RAW_MATERIAL_ACCOUNT",
                        material.getSku(),
                        "Inventory account is not configured"));
              }
            });

    List<ProductionProduct> products =
        productionProductRepository.findByCompanyOrderByProductNameAsc(company);
    for (ProductionProduct product : products) {
      String sku = trimToNull(product.getSkuCode());
      if (sku == null) {
        continue;
      }
      boolean isRawMaterial = isRawMaterialCategory(product.getCategory());
      if (isRawMaterial) {
        RawMaterial material = rawMaterialsBySku.get(sku);
        if (material == null) {
          issues.add(
              issue(
                  company,
                  "RAW_MATERIAL_MAPPING",
                  sku,
                  "Production catalog entry missing linked raw material"));
        }
        continue;
      }
      FinishedGood finishedGood = finishedGoodsBySku.remove(sku);
      if (finishedGood != null) {
        checkFinishedGoodAccounts(company, finishedGood, issues);
      } else {
        checkFinishedGoodMetadataFallback(company, product, sku, issues);
      }
      checkProductionMetadata(company, product, sku, issues);
    }

    finishedGoodsBySku
        .values()
        .forEach(finishedGood -> checkFinishedGoodAccounts(company, finishedGood, issues));
  }

  private void checkTaxAccounts(Company company, List<ConfigurationIssue> issues) {
    if (isNonGstMode(company)) {
      List<String> configured = new ArrayList<>();
      if (company.getGstInputTaxAccountId() != null) {
        configured.add("gstInputTaxAccountId");
      }
      if (company.getGstOutputTaxAccountId() != null) {
        configured.add("gstOutputTaxAccountId");
      }
      if (company.getGstPayableAccountId() != null) {
        configured.add("gstPayableAccountId");
      }
      if (!configured.isEmpty()) {
        issues.add(
            issue(
                company,
                "TAX_ACCOUNT",
                "NON_GST_MODE",
                "Non-GST mode company cannot have GST tax accounts configured: "
                    + String.join(", ", configured)));
      }
      return;
    }

    if (company.getGstInputTaxAccountId() == null) {
      issues.add(
          issue(company, "TAX_ACCOUNT", "GST_INPUT", "GST input tax account is not configured"));
    }
    if (company.getGstOutputTaxAccountId() == null) {
      issues.add(
          issue(company, "TAX_ACCOUNT", "GST_OUTPUT", "GST output tax account is not configured"));
    }
    if (company.getGstPayableAccountId() == null) {
      issues.add(
          issue(company, "TAX_ACCOUNT", "GST_PAYABLE", "GST payable account is not configured"));
    }
  }

  private void checkBaseCurrency(Company company, List<ConfigurationIssue> issues) {
    if (!StringUtils.hasText(company.getBaseCurrency())) {
      issues.add(issue(company, "BASE_CURRENCY", "BASE", "Base currency is not configured"));
    }
  }

  private void checkDefaultAccounts(Company company, List<ConfigurationIssue> issues) {
    List<String> missing = new ArrayList<>();
    if (company.getDefaultInventoryAccountId() == null) {
      missing.add("defaultInventoryAccountId");
    }
    if (company.getDefaultCogsAccountId() == null) {
      missing.add("defaultCogsAccountId");
    }
    if (company.getDefaultRevenueAccountId() == null) {
      missing.add("defaultRevenueAccountId");
    }
    if (company.getDefaultDiscountAccountId() == null) {
      missing.add("defaultDiscountAccountId");
    }
    if (company.getDefaultTaxAccountId() == null) {
      missing.add("defaultTaxAccountId");
    }
    if (!missing.isEmpty()) {
      issues.add(
          issue(
              company,
              "DEFAULT_ACCOUNTS",
              "COMPANY_DEFAULTS",
              "Missing default account IDs: " + String.join(", ", missing)));
    }
  }

  private void checkFinishedGoodAccounts(
      Company company, FinishedGood finishedGood, List<ConfigurationIssue> issues) {
    List<String> missingAccountWiring = new ArrayList<>();
    if (finishedGood.getValuationAccountId() == null) {
      missingAccountWiring.add("valuation");
    }
    if (finishedGood.getRevenueAccountId() == null) {
      missingAccountWiring.add("revenue");
    }
    if (finishedGood.getTaxAccountId() == null) {
      missingAccountWiring.add("tax");
    }
    if (!missingAccountWiring.isEmpty()) {
      issues.add(
          issue(
              company,
              "FINISHED_GOOD_ACCOUNT",
              finishedGood.getProductCode(),
              "Missing finished-good account wiring: " + String.join(", ", missingAccountWiring)));
    }
  }

  private void checkFinishedGoodMetadataFallback(
      Company company, ProductionProduct product, String sku, List<ConfigurationIssue> issues) {
    Long valuationAccountId = metadataLong(product, "fgValuationAccountId");
    Long revenueAccountId = metadataLong(product, "fgRevenueAccountId");
    Long taxAccountId = metadataLong(product, "fgTaxAccountId");
    List<String> missingAccountWiring = new ArrayList<>();
    if (valuationAccountId == null) {
      missingAccountWiring.add("valuation");
    }
    if (revenueAccountId == null) {
      missingAccountWiring.add("revenue");
    }
    if (taxAccountId == null) {
      missingAccountWiring.add("tax");
    }
    if (!missingAccountWiring.isEmpty()) {
      issues.add(
          issue(
              company,
              "FINISHED_GOOD_ACCOUNT",
              sku,
              "Finished good not seeded and production metadata missing account wiring: "
                  + String.join(", ", missingAccountWiring)));
    }
  }

  private void checkProductionMetadata(
      Company company, ProductionProduct product, String sku, List<ConfigurationIssue> issues) {
    for (String key : REQUIRED_PRODUCT_METADATA_KEYS) {
      if (metadataLong(product, key) == null) {
        issues.add(issue(company, "PRODUCTION_METADATA", sku, "Missing " + key + " metadata"));
      }
    }
  }

  private ConfigurationIssue issue(
      Company company, String domain, String reference, String message) {
    return new ConfigurationIssue(company.getCode(), domain, reference, message);
  }

  private boolean isRawMaterialCategory(String category) {
    if (!StringUtils.hasText(category)) {
      return false;
    }
    String normalized = category.trim().toUpperCase();
    return RAW_MATERIAL_CATEGORIES.contains(normalized);
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isNonGstMode(Company company) {
    BigDecimal defaultGstRate = company.getDefaultGstRate();
    return defaultGstRate != null && defaultGstRate.compareTo(BigDecimal.ZERO) == 0;
  }

  private Long metadataLong(ProductionProduct product, String key) {
    if (product.getMetadata() == null) {
      return null;
    }
    Object value = product.getMetadata().get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException ex) {
        log.warn("Unable to parse metadata {}={} for product {}", key, text, product.getSkuCode());
        return null;
      }
    }
    return null;
  }

  private String buildFailureMessage(ConfigurationHealthReport report) {
    String issues =
        report.issues().stream()
            .limit(10)
            .map(
                issue ->
                    issue.companyCode()
                        + ":"
                        + issue.domain()
                        + ":"
                        + issue.reference()
                        + " - "
                        + issue.message())
            .collect(Collectors.joining("; "));
    if (report.issues().size() > 10) {
      issues = issues + "; ... (" + report.issues().size() + " total issues)";
    }
    return "Configuration validation failed: " + issues;
  }

  public record ConfigurationIssue(
      String companyCode, String domain, String reference, String message) {}

  public record ConfigurationHealthReport(boolean healthy, List<ConfigurationIssue> issues) {}
}
