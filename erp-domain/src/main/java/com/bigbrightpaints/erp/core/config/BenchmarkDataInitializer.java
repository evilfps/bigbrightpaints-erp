package com.bigbrightpaints.erp.core.config;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Benchmark Data Initializer for BigBright Paints ERP.
 * 
 * Creates clean master data for BBP company without any journal entries.
 * This allows the benchmark runner to create all transactions from scratch,
 * enabling accurate COGS and inventory costing validation.
 * 
 * Key differences from MockDataInitializer:
 * - NO pre-seeded journal entries (no MOCK-SALE, MOCK-COGS, MOCK-PO)
 * - NO pre-seeded inventory batches
 * - Only creates master data: accounts, products, suppliers, dealers
 * - All costing flows start from zero
 * 
 * Products created match transactions.csv:
 * - Raw Materials: RM-WB, RM-TIO2, RM-BINDER, RM-COLORANT
 * - Packaging: PK-5L-CAN, PK-10L-CAN, PK-LABEL
 * - Semi-Finished: SF-PEE-BULK (bulk paint, auto-created from SF-PEE product)
 * - Finished Goods: FG-PEE-5L, FG-PEE-10L
 * 
 * Accounts use standard chart:
 * - 1200: Raw Materials Inventory
 * - 1300: WIP - Mixing
 * - 1310: WIP - Packing
 * - 1400: Semi-Finished Goods
 * - 1500: Finished Goods Inventory
 * - 1600: Packaging Materials
 * - 5000: Cost of Goods Sold
 * - 4000: Revenue
 * - 2100: GST Output
 * - 2000: Accounts Payable
 * - 1100: Accounts Receivable
 */
@Configuration
@Profile("benchmark")
public class BenchmarkDataInitializer {

    @Bean
    CommandLineRunner seedBenchmarkData(CompanyRepository companyRepository,
                                        RoleRepository roleRepository,
                                        UserAccountRepository userRepository,
                                        PasswordEncoder passwordEncoder,
                                        AccountRepository accountRepository,
                                        DealerRepository dealerRepository,
                                        SupplierRepository supplierRepository,
                                        ProductionBrandRepository brandRepository,
                                        ProductionProductRepository productRepository,
                                        FinishedGoodRepository finishedGoodRepository,
                                        RawMaterialRepository rawMaterialRepository) {
        return args -> {
            System.out.println("=== Benchmark Data Initializer Starting ===");
            
            // 1. Create or get BBP company
            Company company = createCompany(companyRepository);
            System.out.println("Company: " + company.getCode() + " (ID: " + company.getId() + ")");
            
            // 2. Create chart of accounts
            Map<String, Account> accounts = seedAccounts(company, accountRepository, companyRepository);
            System.out.println("Accounts created: " + accounts.size());
            
            // 3. Create roles and admin user
            seedRolesAndUsers(roleRepository, userRepository, passwordEncoder, company);
            System.out.println("Admin user created: benchmark.admin@bbp.com");
            
            // 4. Create suppliers
            seedSuppliers(company, supplierRepository, accounts.get("AP"));
            System.out.println("Suppliers created");
            
            // 5. Create dealers
            seedDealers(company, dealerRepository, accounts.get("AR"));
            System.out.println("Dealers created");
            
            // 6. Create production brand
            ProductionBrand brand = seedBrand(company, brandRepository);
            System.out.println("Brand: " + brand.getCode());
            
            // 7. Create raw materials (no batches - benchmark will create via purchases)
            seedRawMaterials(company, rawMaterialRepository, accounts);
            System.out.println("Raw materials created");
            
            // 8. Create finished goods (no batches - benchmark will create via production)
            seedFinishedGoods(company, finishedGoodRepository, productRepository, accounts, brand);
            System.out.println("Finished goods created");
            
            System.out.println("=== Benchmark Data Initializer Complete ===");
            System.out.println("Ready for benchmark transactions. No journals pre-seeded.");
        };
    }

    private Company createCompany(CompanyRepository companyRepository) {
        return companyRepository.findByCodeIgnoreCase("BBP")
                .orElseGet(() -> {
                    Company c = new Company();
                    c.setCode("BBP");
                    c.setName("Big Bright Paints Ltd");
                    c.setTimezone("Asia/Kolkata");
                    c.setDefaultGstRate(new BigDecimal("18"));
                    c.setBaseCurrency("INR");
                    return companyRepository.save(c);
                });
    }

    private Map<String, Account> seedAccounts(Company company,
                                              AccountRepository accountRepository,
                                              CompanyRepository companyRepository) {
        Map<String, Account> map = new HashMap<>();
        
        // Asset accounts
        map.put("CASH", ensureAccount(company, "1000", "Cash", AccountType.ASSET, accountRepository));
        map.put("AR", ensureAccount(company, "1100", "Accounts Receivable", AccountType.ASSET, accountRepository));
        map.put("RM_INV", ensureAccount(company, "1200", "Raw Materials Inventory", AccountType.ASSET, accountRepository));
        map.put("WIP_MIX", ensureAccount(company, "1300", "WIP - Mixing", AccountType.ASSET, accountRepository));
        map.put("WIP_PACK", ensureAccount(company, "1310", "WIP - Packing", AccountType.ASSET, accountRepository));
        map.put("SF_INV", ensureAccount(company, "1400", "Semi-Finished Goods", AccountType.ASSET, accountRepository));
        map.put("FG_INV", ensureAccount(company, "1500", "Finished Goods Inventory", AccountType.ASSET, accountRepository));
        map.put("PKG_INV", ensureAccount(company, "1600", "Packaging Materials", AccountType.ASSET, accountRepository));
        map.put("GST_IN", ensureAccount(company, "1700", "GST Input Tax", AccountType.ASSET, accountRepository));
        
        // Liability accounts
        map.put("AP", ensureAccount(company, "2000", "Accounts Payable", AccountType.LIABILITY, accountRepository));
        map.put("GST_OUT", ensureAccount(company, "2100", "GST Output Tax", AccountType.LIABILITY, accountRepository));
        map.put("GST_PAY", ensureAccount(company, "2200", "GST Payable", AccountType.LIABILITY, accountRepository));
        
        // Revenue accounts
        map.put("REV", ensureAccount(company, "4000", "Revenue", AccountType.REVENUE, accountRepository));
        map.put("DISC", ensureAccount(company, "4100", "Discounts Given", AccountType.REVENUE, accountRepository));
        
        // Expense/COGS accounts
        map.put("COGS", ensureAccount(company, "5000", "Cost of Goods Sold", AccountType.COGS, accountRepository));
        map.put("LABOR", ensureAccount(company, "5100", "Direct Labor", AccountType.EXPENSE, accountRepository));
        map.put("OVERHEAD", ensureAccount(company, "5200", "Manufacturing Overhead", AccountType.EXPENSE, accountRepository));
        map.put("WASTAGE", ensureAccount(company, "5300", "Material Wastage", AccountType.EXPENSE, accountRepository));
        
        // Set company defaults
        company.setGstInputTaxAccountId(map.get("GST_IN").getId());
        company.setGstOutputTaxAccountId(map.get("GST_OUT").getId());
        company.setGstPayableAccountId(map.get("GST_PAY").getId());
        company.setDefaultInventoryAccountId(map.get("FG_INV").getId());
        company.setDefaultCogsAccountId(map.get("COGS").getId());
        company.setDefaultRevenueAccountId(map.get("REV").getId());
        company.setDefaultTaxAccountId(map.get("GST_OUT").getId());  // Sales tax
        company.setDefaultDiscountAccountId(map.get("DISC").getId());  // Discounts
        companyRepository.save(company);
        
        return map;
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type, AccountRepository repo) {
        return repo.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account a = new Account();
                    a.setCompany(company);
                    a.setCode(code);
                    a.setName(name);
                    a.setType(type);
                    a.setBalance(BigDecimal.ZERO);
                    return repo.save(a);
                });
    }

    private void seedRolesAndUsers(RoleRepository roleRepository,
                                   UserAccountRepository userRepository,
                                   PasswordEncoder encoder,
                                   Company company) {
        Role admin = roleRepository.findByName("ROLE_ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setName("ROLE_ADMIN");
            r.setDescription("Administrator");
            return roleRepository.save(r);
        });
        Role accounting = roleRepository.findByName("ROLE_ACCOUNTING").orElseGet(() -> {
            Role r = new Role();
            r.setName("ROLE_ACCOUNTING");
            r.setDescription("Accounting");
            return roleRepository.save(r);
        });
        Role sales = roleRepository.findByName("ROLE_SALES").orElseGet(() -> {
            Role r = new Role();
            r.setName("ROLE_SALES");
            r.setDescription("Sales");
            return roleRepository.save(r);
        });
        Role factory = roleRepository.findByName("ROLE_FACTORY").orElseGet(() -> {
            Role r = new Role();
            r.setName("ROLE_FACTORY");
            r.setDescription("Factory Operations");
            return roleRepository.save(r);
        });

        userRepository.findByEmailIgnoreCase("benchmark.admin@bbp.com").orElseGet(() -> {
            UserAccount user = new UserAccount(
                    "benchmark.admin@bbp.com",
                    encoder.encode("Benchmark123!"),
                    "Benchmark Admin");
            user.addCompany(company);
            user.addRole(admin);
            user.addRole(accounting);
            user.addRole(sales);
            user.addRole(factory);
            return userRepository.save(user);
        });
    }

    private void seedSuppliers(Company company, SupplierRepository supplierRepository, Account ap) {
        String[][] suppliers = {
            {"SUP-001", "Chemical Base Suppliers", "sup1@example.com"},
            {"SUP-002", "TiO2 Industries", "sup2@example.com"},
            {"SUP-003", "Colorant Chemicals", "sup3@example.com"},
            {"SUP-004", "Packaging Solutions", "sup4@example.com"},
            {"SUP-005", "Label Printers Ltd", "sup5@example.com"}
        };
        
        for (String[] s : suppliers) {
            supplierRepository.findByCompanyAndCodeIgnoreCase(company, s[0])
                    .orElseGet(() -> {
                        Supplier sup = new Supplier();
                        sup.setCompany(company);
                        sup.setCode(s[0]);
                        sup.setName(s[1]);
                        sup.setEmail(s[2]);
                        sup.setPayableAccount(ap);
                        sup.setCreditLimit(new BigDecimal("1000000"));
                        sup.setOutstandingBalance(BigDecimal.ZERO);
                        return supplierRepository.save(sup);
                    });
        }
    }

    private void seedDealers(Company company, DealerRepository dealerRepository, Account ar) {
        String[][] dealers = {
            {"DLR-001", "PaintMart Distributors", "dealer1@example.com"},
            {"DLR-002", "Home Depot Partner", "dealer2@example.com"},
            {"DLR-003", "Builder's Choice", "dealer3@example.com"}
        };
        
        for (String[] d : dealers) {
            dealerRepository.findByCompanyAndCodeIgnoreCase(company, d[0])
                    .orElseGet(() -> {
                        Dealer dlr = new Dealer();
                        dlr.setCompany(company);
                        dlr.setCode(d[0]);
                        dlr.setName(d[1]);
                        dlr.setEmail(d[2]);
                        dlr.setReceivableAccount(ar);
                        dlr.setCreditLimit(new BigDecimal("2000000"));
                        dlr.setOutstandingBalance(BigDecimal.ZERO);
                        return dealerRepository.save(dlr);
                    });
        }
    }

    private ProductionBrand seedBrand(Company company, ProductionBrandRepository brandRepository) {
        return brandRepository.findByCompanyAndCodeIgnoreCase(company, "PEE")
                .orElseGet(() -> {
                    ProductionBrand brand = new ProductionBrand();
                    brand.setCompany(company);
                    brand.setCode("PEE");
                    brand.setName("Premium Exterior Enamel");
                    return brandRepository.save(brand);
                });
    }

    private void seedRawMaterials(Company company, RawMaterialRepository rawMaterialRepository, Map<String, Account> accounts) {
        // Raw materials - matched to transactions.csv
        Object[][] materials = {
            {"RM-WB", "White Base", "L", accounts.get("RM_INV").getId()},
            {"RM-TIO2", "Titanium Dioxide", "KG", accounts.get("RM_INV").getId()},
            {"RM-BINDER", "Acrylic Binder", "L", accounts.get("RM_INV").getId()},
            {"RM-COLORANT", "Color Pigment", "KG", accounts.get("RM_INV").getId()},
            // Packaging materials
            {"PK-5L-CAN", "5L Metal Can", "UNIT", accounts.get("PKG_INV").getId()},
            {"PK-10L-CAN", "10L Metal Can", "UNIT", accounts.get("PKG_INV").getId()},
            {"PK-LABEL", "Product Label", "UNIT", accounts.get("PKG_INV").getId()}
        };
        
        for (Object[] m : materials) {
            String sku = (String) m[0];
            rawMaterialRepository.findByCompanyAndSku(company, sku)
                    .orElseGet(() -> {
                        RawMaterial rm = new RawMaterial();
                        rm.setCompany(company);
                        rm.setSku(sku);
                        rm.setName((String) m[1]);
                        rm.setUnitType((String) m[2]);
                        rm.setInventoryAccountId((Long) m[3]);
                        rm.setReorderLevel(new BigDecimal("50"));
                        rm.setMinStock(new BigDecimal("50"));
                        rm.setMaxStock(new BigDecimal("10000"));
                        rm.setCurrentStock(BigDecimal.ZERO);  // No opening stock
                        return rawMaterialRepository.save(rm);
                    });
        }
    }

    private void seedFinishedGoods(Company company,
                                   FinishedGoodRepository finishedGoodRepository,
                                   ProductionProductRepository productRepository,
                                   Map<String, Account> accounts,
                                   ProductionBrand brand) {
        // Semi-finished (bulk) product
        // Note: ProductionProduct has skuCode="SF-PEE", the system auto-creates SF-PEE-BULK
        // We create both the ProductionProduct (SF-PEE) and FinishedGood (SF-PEE-BULK) 
        ensureFinishedGood(company, finishedGoodRepository, productRepository, accounts, brand,
                "SF-PEE", "Premium Enamel", "L", true,
                accounts.get("SF_INV").getId(), accounts.get("WIP_MIX").getId());
        
        // Finished goods (packed)
        ensureFinishedGood(company, finishedGoodRepository, productRepository, accounts, brand,
                "FG-PEE-5L", "Premium Enamel 5L", "UNIT", false,
                accounts.get("FG_INV").getId(), accounts.get("WIP_PACK").getId());
        
        ensureFinishedGood(company, finishedGoodRepository, productRepository, accounts, brand,
                "FG-PEE-10L", "Premium Enamel 10L", "UNIT", false,
                accounts.get("FG_INV").getId(), accounts.get("WIP_PACK").getId());
    }

    private void ensureFinishedGood(Company company,
                                    FinishedGoodRepository finishedGoodRepository,
                                    ProductionProductRepository productRepository,
                                    Map<String, Account> accounts,
                                    ProductionBrand brand,
                                    String sku,
                                    String name,
                                    String unit,
                                    boolean isBulk,
                                    Long valuationAccountId,
                                    Long wipAccountId) {
        // For bulk products: FinishedGood uses {sku}-BULK, ProductionProduct uses {sku}
        // This matches the system's automatic creation pattern in ProductionLogService
        String fgSku = isBulk ? sku + "-BULK" : sku;
        String fgName = isBulk ? name + " (Bulk)" : name;
        
        // Create FinishedGood entity
        finishedGoodRepository.findByCompanyAndProductCode(company, fgSku)
                .orElseGet(() -> {
                    FinishedGood fg = new FinishedGood();
                    fg.setCompany(company);
                    fg.setProductCode(fgSku);
                    fg.setName(fgName);
                    fg.setUnit(unit);
                    fg.setCostingMethod("FIFO");
                    fg.setValuationAccountId(valuationAccountId);
                    fg.setCogsAccountId(accounts.get("COGS").getId());
                    fg.setRevenueAccountId(accounts.get("REV").getId());
                    fg.setDiscountAccountId(accounts.get("DISC").getId());
                    fg.setTaxAccountId(accounts.get("GST_OUT").getId());
                    fg.setCurrentStock(BigDecimal.ZERO);  // No opening stock
                    fg.setReservedStock(BigDecimal.ZERO);
                    return finishedGoodRepository.save(fg);
                });

        // Create ProductionProduct for production log integration
        // Note: ProductionProduct uses the base sku (without -BULK suffix)
        productRepository.findByCompanyAndSkuCode(company, sku)
                .orElseGet(() -> {
                    ProductionProduct product = new ProductionProduct();
                    product.setCompany(company);
                    product.setBrand(brand);
                    product.setSkuCode(sku);
                    product.setProductName(name);
                    product.setCategory(isBulk ? "SEMI_FINISHED" : "FINISHED_GOOD");
                    product.setUnitOfMeasure(unit);
                    product.setBasePrice(isBulk ? new BigDecimal("100") : new BigDecimal("650"));
                    product.setGstRate(new BigDecimal("18"));
                    product.setMinDiscountPercent(BigDecimal.ZERO);
                    product.setMinSellingPrice(BigDecimal.ZERO);
                    product.setActive(true);
                    
                    // Set metadata for accounting integration
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("wipAccountId", wipAccountId);
                    metadata.put("semiFinishedAccountId", accounts.get("SF_INV").getId());
                    metadata.put("fgValuationAccountId", valuationAccountId);
                    metadata.put("fgCogsAccountId", accounts.get("COGS").getId());
                    metadata.put("fgRevenueAccountId", accounts.get("REV").getId());
                    metadata.put("fgDiscountAccountId", accounts.get("DISC").getId());
                    metadata.put("fgTaxAccountId", accounts.get("GST_OUT").getId());
                    metadata.put("wastageAccountId", accounts.get("WASTAGE").getId());
                    product.setMetadata(metadata);
                    
                    return productRepository.save(product);
                });
    }
}
