package com.bigbrightpaints.erp.test.support;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.TestDataSeeder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CanonicalErpDatasetBuilder {

    private final TestDataSeeder dataSeeder;
    private final CompanyRepository companyRepository;
    private final AccountRepository accountRepository;
    private final DealerRepository dealerRepository;
    private final SupplierRepository supplierRepository;
    private final ProductionBrandRepository brandRepository;
    private final ProductionProductRepository productRepository;
    private final FinishedGoodRepository finishedGoodRepository;

    public CanonicalErpDatasetBuilder(TestDataSeeder dataSeeder,
                                      CompanyRepository companyRepository,
                                      AccountRepository accountRepository,
                                      DealerRepository dealerRepository,
                                      SupplierRepository supplierRepository,
                                      ProductionBrandRepository brandRepository,
                                      ProductionProductRepository productRepository,
                                      FinishedGoodRepository finishedGoodRepository) {
        this.dataSeeder = dataSeeder;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.dealerRepository = dealerRepository;
        this.supplierRepository = supplierRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.finishedGoodRepository = finishedGoodRepository;
    }

    public CanonicalErpDataset seedCompany(String companyCode) {
        dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        Company company = companyRepository.findByCodeIgnoreCase(companyCode)
                .orElseThrow(() -> new IllegalStateException("Company missing: " + companyCode));

        Map<String, Account> accounts = ensureCoreAccounts(company);
        ensureCompanyDefaults(company, accounts);

        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER")
                .orElseThrow(() -> new IllegalStateException("Fixture dealer missing for " + companyCode));
        Supplier supplier = supplierRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-SUP")
                .orElseThrow(() -> new IllegalStateException("Fixture supplier missing for " + companyCode));
        supplier = ensureSupplierActive(supplier);

        ProductionBrand brand = brandRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-BRAND")
                .orElseThrow(() -> new IllegalStateException("Fixture brand missing for " + companyCode));
        ProductionProduct product = productRepository.findByCompanyAndSkuCode(company, "FG-FIXTURE")
                .orElseThrow(() -> new IllegalStateException("Fixture product missing for " + companyCode));
        ensureProductMetadata(product, accounts);
        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, "FG-FIXTURE")
                .orElseThrow(() -> new IllegalStateException("Fixture finished good missing for " + companyCode));
        ensureFinishedGoodAccounts(finishedGood, accounts);

        return new CanonicalErpDataset(company, accounts, dealer, supplier, brand, product, finishedGood);
    }

    public Account ensurePayrollAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private Map<String, Account> ensureCoreAccounts(Company company) {
        Map<String, Account> accounts = new HashMap<>();
        accounts.put("CASH", ensureAccount(company, "CASH", "Cash", AccountType.ASSET));
        accounts.put("AR", ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET));
        accounts.put("AP", ensureAccount(company, "AP", "Accounts Payable", AccountType.LIABILITY));
        accounts.put("INV", ensureAccount(company, "INV", "Inventory", AccountType.ASSET));
        accounts.put("COGS", ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS));
        accounts.put("REV", ensureAccount(company, "REV", "Revenue", AccountType.REVENUE));
        accounts.put("GST_OUT", ensureAccount(company, "GST-OUT", "Output Tax", AccountType.LIABILITY));
        accounts.put("DISC", ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE));
        accounts.put("WIP", ensureAccount(company, "WIP", "Work in Progress", AccountType.ASSET));
        return accounts;
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private void ensureCompanyDefaults(Company company, Map<String, Account> accounts) {
        boolean updated = false;
        if (company.getDefaultInventoryAccountId() == null) {
            company.setDefaultInventoryAccountId(accounts.get("INV").getId());
            updated = true;
        }
        if (company.getDefaultCogsAccountId() == null) {
            company.setDefaultCogsAccountId(accounts.get("COGS").getId());
            updated = true;
        }
        if (company.getDefaultRevenueAccountId() == null) {
            company.setDefaultRevenueAccountId(accounts.get("REV").getId());
            updated = true;
        }
        if (company.getDefaultDiscountAccountId() == null) {
            company.setDefaultDiscountAccountId(accounts.get("DISC").getId());
            updated = true;
        }
        if (company.getDefaultTaxAccountId() == null) {
            company.setDefaultTaxAccountId(accounts.get("GST_OUT").getId());
            updated = true;
        }
        if (updated) {
            companyRepository.save(company);
        }
    }

    private Supplier ensureSupplierActive(Supplier supplier) {
        if (supplier.getStatusEnum() == SupplierStatus.ACTIVE) {
            return supplier;
        }
        supplier.setStatus(SupplierStatus.ACTIVE);
        return supplierRepository.save(supplier);
    }

    private void ensureProductMetadata(ProductionProduct product, Map<String, Account> accounts) {
        Map<String, Object> metadata = Optional.ofNullable(product.getMetadata()).orElseGet(HashMap::new);
        metadata.putIfAbsent("wipAccountId", accounts.get("WIP").getId());
        metadata.putIfAbsent("wastageAccountId", accounts.get("COGS").getId());
        metadata.putIfAbsent("semiFinishedAccountId", accounts.get("INV").getId());
        metadata.putIfAbsent("fgValuationAccountId", accounts.get("INV").getId());
        metadata.putIfAbsent("fgCogsAccountId", accounts.get("COGS").getId());
        metadata.putIfAbsent("fgRevenueAccountId", accounts.get("REV").getId());
        metadata.putIfAbsent("fgDiscountAccountId", accounts.get("DISC").getId());
        metadata.putIfAbsent("fgTaxAccountId", accounts.get("GST_OUT").getId());
        product.setMetadata(metadata);
        productRepository.save(product);
    }

    private void ensureFinishedGoodAccounts(FinishedGood finishedGood, Map<String, Account> accounts) {
        boolean updated = false;
        if (finishedGood.getValuationAccountId() == null) {
            finishedGood.setValuationAccountId(accounts.get("INV").getId());
            updated = true;
        }
        if (finishedGood.getCogsAccountId() == null) {
            finishedGood.setCogsAccountId(accounts.get("COGS").getId());
            updated = true;
        }
        if (finishedGood.getRevenueAccountId() == null) {
            finishedGood.setRevenueAccountId(accounts.get("REV").getId());
            updated = true;
        }
        if (finishedGood.getDiscountAccountId() == null) {
            finishedGood.setDiscountAccountId(accounts.get("DISC").getId());
            updated = true;
        }
        if (finishedGood.getTaxAccountId() == null) {
            finishedGood.setTaxAccountId(accounts.get("GST_OUT").getId());
            updated = true;
        }
        if (updated) {
            finishedGoodRepository.save(finishedGood);
        }
    }
}
