package com.bigbrightpaints.erp.core.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Component
@Profile({"test", "mock", "dev"})
public class CriticalFixtureService {

  private final AccountingPeriodService accountingPeriodService;
  private final AccountRepository accountRepository;
  private final DealerRepository dealerRepository;
  private final SupplierRepository supplierRepository;
  private final ProductionBrandRepository brandRepository;
  private final ProductionProductRepository productRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final CompanyRepository companyRepository;

  public CriticalFixtureService(
      AccountingPeriodService accountingPeriodService,
      AccountRepository accountRepository,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      ProductionBrandRepository brandRepository,
      ProductionProductRepository productRepository,
      FinishedGoodRepository finishedGoodRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      CompanyRepository companyRepository) {
    this.accountingPeriodService = accountingPeriodService;
    this.accountRepository = accountRepository;
    this.dealerRepository = dealerRepository;
    this.supplierRepository = supplierRepository;
    this.brandRepository = brandRepository;
    this.productRepository = productRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.companyRepository = companyRepository;
  }

  @Transactional
  public void seedCompanyFixtures(Company company) {
    if (company == null) {
      return;
    }
    if (company.getTimezone() == null) {
      company.setTimezone("UTC");
    }
    if (company.getBaseCurrency() == null) {
      company.setBaseCurrency("INR");
    }
    companyRepository.save(company);

    accountingPeriodService.ensurePeriod(company, CompanyTime.today(company));

    Map<String, Account> accounts = ensureAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));
    Supplier supplier = ensureSupplier(company, accounts.get("AP"));

    ProductionBrand brand = ensureBrand(company);
    ProductionProduct product = ensureProductionProduct(company, brand, accounts);
    FinishedGood finishedGood = ensureFinishedGood(company, product, accounts);
    seedBatch(finishedGood, new BigDecimal("150"), new BigDecimal("12.50"));

    if (dealer.getReceivableAccount() == null) {
      dealer.setReceivableAccount(accounts.get("AR"));
      dealerRepository.save(dealer);
    }
    if (supplier.getPayableAccount() == null) {
      supplier.setPayableAccount(accounts.get("AP"));
      supplierRepository.save(supplier);
    }
  }

  private Map<String, Account> ensureAccounts(Company company) {
    Map<String, Account> map = new HashMap<>();
    map.put("CASH", ensureAccount(company, "CASH", "Cash", AccountType.ASSET));
    map.put("AR", ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET));
    map.put("AP", ensureAccount(company, "AP", "Accounts Payable", AccountType.LIABILITY));
    map.put("INV", ensureAccount(company, "INV", "Inventory", AccountType.ASSET));
    map.put("COGS", ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS));
    map.put("REV", ensureAccount(company, "REV", "Revenue", AccountType.REVENUE));
    map.put("GST_OUT", ensureAccount(company, "GST-OUT", "Output Tax", AccountType.LIABILITY));
    map.put("GST_IN", ensureAccount(company, "GST-IN", "Input Tax", AccountType.ASSET));
    map.put("DISC", ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE));
    map.put("WIP", ensureAccount(company, "WIP", "Work in Progress", AccountType.ASSET));

    if (company.getGstOutputTaxAccountId() == null) {
      company.setGstOutputTaxAccountId(map.get("GST_OUT").getId());
    }
    if (company.getGstInputTaxAccountId() == null) {
      company.setGstInputTaxAccountId(map.get("GST_IN").getId());
    }
    companyRepository.save(company);
    return map;
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
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private Dealer ensureDealer(Company company, Account receivable) {
    Dealer dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER")
            .orElseGet(
                () -> {
                  Dealer d = new Dealer();
                  d.setCompany(company);
                  d.setName("Fixture Dealer");
                  d.setCode("FIX-DEALER");
                  d.setOutstandingBalance(BigDecimal.ZERO);
                  return dealerRepository.save(d);
                });
    dealer.setReceivableAccount(receivable);
    return dealerRepository.save(dealer);
  }

  private Supplier ensureSupplier(Company company, Account payable) {
    Supplier supplier =
        supplierRepository
            .findByCompanyAndCodeIgnoreCase(company, "FIX-SUP")
            .orElseGet(
                () -> {
                  Supplier s = new Supplier();
                  s.setCompany(company);
                  s.setName("Fixture Supplier");
                  s.setCode("FIX-SUP");
                  return supplierRepository.save(s);
                });
    supplier.setPayableAccount(payable);
    supplier.setStatus("ACTIVE");
    return supplierRepository.save(supplier);
  }

  private ProductionBrand ensureBrand(Company company) {
    return brandRepository
        .findByCompanyAndCodeIgnoreCase(company, "FIX-BRAND")
        .orElseGet(
            () -> {
              ProductionBrand brand = new ProductionBrand();
              brand.setCompany(company);
              brand.setCode("FIX-BRAND");
              brand.setName("Fixture Brand");
              return brandRepository.save(brand);
            });
  }

  private ProductionProduct ensureProductionProduct(
      Company company, ProductionBrand brand, Map<String, Account> accounts) {
    ProductionProduct product =
        productRepository
            .findByCompanyAndSkuCode(company, "FG-FIXTURE")
            .orElseGet(
                () -> {
                  ProductionProduct created = new ProductionProduct();
                  created.setCompany(company);
                  created.setBrand(brand);
                  created.setSkuCode("FG-FIXTURE");
                  created.setProductName("Fixture Finished Good");
                  created.setCategory("FINISHED_GOOD");
                  created.setUnitOfMeasure("UNIT");
                  created.setBasePrice(new BigDecimal("100.00"));
                  created.setGstRate(new BigDecimal("18"));
                  return productRepository.save(created);
                });

    Map<String, Object> metadata =
        Optional.ofNullable(product.getMetadata()).orElse(new HashMap<>());
    metadata.putIfAbsent("wipAccountId", accounts.get("WIP").getId());
    metadata.putIfAbsent("wastageAccountId", accounts.get("COGS").getId());
    metadata.putIfAbsent("semiFinishedAccountId", accounts.get("INV").getId());
    metadata.putIfAbsent("fgValuationAccountId", accounts.get("INV").getId());
    metadata.putIfAbsent("fgCogsAccountId", accounts.get("COGS").getId());
    metadata.putIfAbsent("fgRevenueAccountId", accounts.get("REV").getId());
    metadata.putIfAbsent("fgDiscountAccountId", accounts.get("DISC").getId());
    metadata.putIfAbsent("fgTaxAccountId", accounts.get("GST_OUT").getId());
    product.setMetadata(metadata);
    return productRepository.save(product);
  }

  private FinishedGood ensureFinishedGood(
      Company company, ProductionProduct product, Map<String, Account> accounts) {
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, product.getSkuCode())
            .orElseGet(
                () -> {
                  FinishedGood created = new FinishedGood();
                  created.setCompany(company);
                  created.setProductCode(product.getSkuCode());
                  created.setName(product.getProductName());
                  created.setUnit("UNIT");
                  created.setCostingMethod("FIFO");
                  created.setValuationAccountId(accounts.get("INV").getId());
                  created.setCogsAccountId(accounts.get("COGS").getId());
                  created.setRevenueAccountId(accounts.get("REV").getId());
                  created.setDiscountAccountId(accounts.get("DISC").getId());
                  created.setTaxAccountId(accounts.get("GST_OUT").getId());
                  created.setCurrentStock(BigDecimal.ZERO);
                  created.setReservedStock(BigDecimal.ZERO);
                  return finishedGoodRepository.save(created);
                });
    return fg;
  }

  private void seedBatch(FinishedGood finishedGood, BigDecimal quantity, BigDecimal unitCost) {
    if (finishedGood.getId() == null) {
      return;
    }
    boolean exists =
        finishedGoodBatchRepository
            .findByFinishedGood_ValuationAccountId(finishedGood.getValuationAccountId())
            .stream()
            .anyMatch(b -> finishedGood.getId().equals(b.getFinishedGood().getId()));
    if (exists) {
      return;
    }
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(finishedGood);
    batch.setBatchCode("FIX-BATCH-1");
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(CompanyTime.now(finishedGood.getCompany()));
    finishedGoodBatchRepository.save(batch);

    BigDecimal current =
        Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
    finishedGood.setCurrentStock(current.add(quantity));
    finishedGoodRepository.save(finishedGood);
  }
}
