package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class CR_MfgProducedAtTimezoneTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private ProductionLogService productionLogService;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private CompanyClock companyClock;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void producedAt_usesCompanyTimezone_forLocalInputsAroundMidnight() {
    String companyCode = "CR-MFG-TZ-" + shortId();
    Company company = bootstrapCompany(companyCode, "America/Los_Angeles");
    Map<String, Account> accounts = ensureManufacturingAccounts(company);
    ProductionProduct product = ensureProductionProduct(company, accounts, "CR-FG-" + shortId());

    RawMaterial material = ensureRawMaterial(company, accounts.get("RM_INV"), "CR-RM-" + shortId());
    ensureRawMaterialBatch(material, new BigDecimal("100"), new BigDecimal("5.00"));

    LocalDate today = companyClock.today(company);
    String localDate = today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    CompanyContextHolder.setCompanyId(companyCode);
    ProductionLog first =
        productionLogRepository
            .findById(
                productionLogService
                    .createLog(
                        new ProductionLogRequest(
                            product.getBrand().getId(),
                            product.getId(),
                            "Blue",
                            new BigDecimal("10"),
                            "L",
                            new BigDecimal("10"),
                            localDate + " 00:30",
                            "timezone-test",
                            "codered",
                            null,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            List.of(
                                new ProductionLogRequest.MaterialUsageRequest(
                                    material.getId(),
                                    new BigDecimal("5"),
                                    material.getUnitType()))))
                    .id())
            .orElseThrow();

    ProductionLog second =
        productionLogRepository
            .findById(
                productionLogService
                    .createLog(
                        new ProductionLogRequest(
                            product.getBrand().getId(),
                            product.getId(),
                            "Blue",
                            new BigDecimal("10"),
                            "L",
                            new BigDecimal("10"),
                            today.toString(),
                            "timezone-test",
                            "codered",
                            null,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            List.of(
                                new ProductionLogRequest.MaterialUsageRequest(
                                    material.getId(),
                                    new BigDecimal("5"),
                                    material.getUnitType()))))
                    .id())
            .orElseThrow();
    CompanyContextHolder.clear();

    ZoneId zone = ZoneId.of("America/Los_Angeles");
    assertThat(first.getProducedAt().atZone(zone).toLocalDate())
        .as("Early-morning local time stays on same business date")
        .isEqualTo(today);
    assertThat(second.getProducedAt().atZone(zone).toLocalDate())
        .as("Date-only input uses company timezone")
        .isEqualTo(today);

    JournalEntry firstJournal =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, first.getProductionCode() + "-RM")
            .orElseThrow();
    JournalEntry secondJournal =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, second.getProductionCode() + "-RM")
            .orElseThrow();

    assertThat(firstJournal.getEntryDate()).isEqualTo(today);
    assertThat(secondJournal.getEntryDate()).isEqualTo(today);
  }

  private Company bootstrapCompany(String companyCode, String timezone) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyId(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone(timezone);
    company.setBaseCurrency("INR");
    return companyRepository.save(company);
  }

  private Map<String, Account> ensureManufacturingAccounts(Company company) {
    Account wip = ensureAccount(company, "WIP", "Work in Progress", AccountType.ASSET);
    Account sf = ensureAccount(company, "SF-INV", "Semi-Finished Inventory", AccountType.ASSET);
    Account fg = ensureAccount(company, "FG-INV", "Finished Goods Inventory", AccountType.ASSET);
    Account rm = ensureAccount(company, "RM-INV", "Raw Material Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS", "COGS", AccountType.COGS);
    Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
    Account gstOut = ensureAccount(company, "GST-OUT", "GST Output", AccountType.LIABILITY);

    return Map.of(
        "WIP", wip,
        "SF_INV", sf,
        "FG_INV", fg,
        "RM_INV", rm,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut);
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              account.setActive(true);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private ProductionProduct ensureProductionProduct(
      Company company, Map<String, Account> accounts, String sku) {
    ProductionBrand brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(company, "CR-BRAND")
            .orElseGet(
                () -> {
                  ProductionBrand b = new ProductionBrand();
                  b.setCompany(company);
                  b.setCode("CR-BRAND");
                  b.setName("Code-Red Brand");
                  return productionBrandRepository.save(b);
                });

    return productionProductRepository
        .findByCompanyAndSkuCode(company, sku)
        .orElseGet(
            () -> {
              ProductionProduct p = new ProductionProduct();
              p.setCompany(company);
              p.setBrand(brand);
              p.setSkuCode(sku);
              p.setProductName("Code-Red " + sku);
              p.setCategory("FINISHED_GOOD");
              p.setUnitOfMeasure("L");
              p.setGstRate(BigDecimal.ZERO);
              Map<String, Object> metadata = new HashMap<>();
              metadata.put("wipAccountId", accounts.get("WIP").getId());
              metadata.put("semiFinishedAccountId", accounts.get("SF_INV").getId());
              metadata.put("fgValuationAccountId", accounts.get("FG_INV").getId());
              metadata.put("fgCogsAccountId", accounts.get("COGS").getId());
              metadata.put("fgRevenueAccountId", accounts.get("REV").getId());
              metadata.put("fgDiscountAccountId", accounts.get("DISC").getId());
              metadata.put("fgTaxAccountId", accounts.get("GST_OUT").getId());
              metadata.put("wastageAccountId", accounts.get("COGS").getId());
              p.setMetadata(metadata);
              return productionProductRepository.save(p);
            });
  }

  private RawMaterial ensureRawMaterial(Company company, Account inventoryAccount, String sku) {
    return rawMaterialRepository
        .findByCompanyAndSku(company, sku)
        .orElseGet(
            () -> {
              RawMaterial rm = new RawMaterial();
              rm.setCompany(company);
              rm.setSku(sku);
              rm.setName("Raw " + sku);
              rm.setUnitType("KG");
              rm.setMaterialType(MaterialType.PRODUCTION);
              rm.setInventoryAccountId(inventoryAccount.getId());
              rm.setCurrentStock(new BigDecimal("100"));
              return rawMaterialRepository.save(rm);
            });
  }

  private void ensureRawMaterialBatch(
      RawMaterial material, BigDecimal quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(material);
    batch.setBatchCode(material.getSku() + "-B1");
    batch.setQuantity(quantity);
    batch.setUnit(material.getUnitType());
    batch.setCostPerUnit(costPerUnit);
    rawMaterialBatchRepository.save(batch);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
