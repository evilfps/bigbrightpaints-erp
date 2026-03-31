package com.bigbrightpaints.erp.core.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequestRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketCategory;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesFulfillmentService;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderCrudService;

@Configuration
@Profile("mock")
public class MockDataInitializer {

  private static final Logger log = LoggerFactory.getLogger(MockDataInitializer.class);
  private static final String DEFAULT_STATE_CODE = "MH";
  private static final String READY_CONFIRM_ORDER_IDEMPOTENCY_KEY = "mock-ready-confirm-order";
  private static final String APPROVAL_EXPORT_REPORT_TYPE = "SALES_REGISTER";
  private static final String APPROVAL_EXPORT_PARAMETERS = "{\"seed\":\"mock-validation-export\"}";
  private static final String APPROVAL_SUPPORT_SUBJECT = "Validation seeded support ticket";
  private static final String APPROVAL_SUPPORT_DESCRIPTION =
      "Seeded support ticket for admin cross-surface UAT.";
  private static final String APPROVAL_CREDIT_REASON = "Validation seeded dealer credit request";
  private static final String P2P_ORDER_NUMBER = "MOCK-P2P-PO-001";
  private static final String P2P_RECEIPT_NUMBER = "MOCK-P2P-GRN-001";
  private static final String P2P_RECEIPT_IDEMPOTENCY_KEY = "mock-p2p-grn-001";
  private static final String P2P_INVOICE_NUMBER = "MOCK-P2P-INV-001";
  private static final String P2P_BATCH_CODE = "RM-RESIN-MOCK-P2P-001";
  private static final LocalDate P2P_ORDER_DATE = LocalDate.of(2026, 2, 10);
  private static final LocalDate P2P_RECEIPT_DATE = LocalDate.of(2026, 2, 12);
  private static final LocalDate P2P_INVOICE_DATE = LocalDate.of(2026, 2, 14);

  @Bean
  CommandLineRunner seedMockData(
      CompanyRepository companyRepository,
      RoleRepository roleRepository,
      UserAccountRepository userRepository,
      PasswordEncoder passwordEncoder,
      AccountRepository accountRepository,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      ProductionBrandRepository brandRepository,
      ProductionProductRepository productRepository,
      FinishedGoodRepository finishedGoodRepository,
      FinishedGoodBatchRepository batchRepository,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      AccountingService accountingService,
      SalesOrderCrudService salesOrderCrudService,
      SalesFulfillmentService salesFulfillmentService,
      PurchasingService purchasingService,
      PurchaseOrderRepository purchaseOrderRepository,
      GoodsReceiptRepository goodsReceiptRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      ExportRequestRepository exportRequestRepository,
      SupportTicketRepository supportTicketRepository,
      CreditRequestRepository creditRequestRepository,
      JournalEntryRepository journalEntryRepository,
      @Value("${erp.seed.mock-admin.email:}") String mockAdminEmail,
      @Value("${erp.seed.mock-admin.password:}") String mockAdminPassword) {
    return args -> {
      Company company = createCompany(companyRepository);
      Map<String, Account> accounts = seedAccounts(company, accountRepository, companyRepository);
      UserAccount seededAdmin =
          seedRolesAndUsers(
              roleRepository,
              userRepository,
              passwordEncoder,
              company,
              mockAdminEmail,
              mockAdminPassword);
      attachMainAdmin(companyRepository, company, seededAdmin);
      Dealer dealer = seedDealer(company, dealerRepository, accounts.get("AR"));
      Supplier supplier = seedSupplier(company, supplierRepository, accounts.get("AP"));
      ProductionBrand brand = seedBrand(company, brandRepository);
      // Use WIP_PACK (1180) for finished goods that go through packing stage
      Account wipPackAccount = accounts.get("WIP_PACK");
      FinishedGood fg =
          seedFinishedGood(
              company,
              finishedGoodRepository,
              productRepository,
              accounts,
              brand,
              "FG-GST",
              "FIFO",
              wipPackAccount);
      FinishedGood fgLifo =
          seedFinishedGood(
              company,
              finishedGoodRepository,
              productRepository,
              accounts,
              brand,
              "FG-LIFO",
              "LIFO",
              wipPackAccount);
      FinishedGood fgKit =
          seedFinishedGood(
              company,
              finishedGoodRepository,
              productRepository,
              accounts,
              brand,
              "FG-KIT",
              "FIFO",
              wipPackAccount);
      seedRawMaterials(company, rawMaterialRepository, rawMaterialBatchRepository, accounts);
      seedBatches(company, batchRepository, finishedGoodRepository, fg, fgLifo, fgKit);

      // Seed a handful of journals for UI exploration
      CompanyContextHolder.setCompanyCode(company.getCode());
      try {
        seedReadyToConfirmOrder(salesOrderCrudService, salesFulfillmentService, dealer);
        seedPendingApprovalFixtures(
            company,
            seededAdmin,
            dealer,
            dealerRepository,
            exportRequestRepository,
            supportTicketRepository,
            creditRequestRepository);
        seedProcureToPayFixture(
            company,
            supplier,
            rawMaterialRepository,
            purchasingService,
            purchaseOrderRepository,
            goodsReceiptRepository,
            rawMaterialPurchaseRepository);
        seedSalesPurchaseAndCogs(accountingService, company, dealer, supplier, accounts);
        // Add some traffic to show balances
        for (int i = 0; i < 10; i++) {
          postSimpleSale(
              accountingService,
              company,
              dealer,
              accounts.get("REV"),
              accounts.get("GST_OUT"),
              accounts.get("AR"),
              new BigDecimal("500").add(new BigDecimal(i * 25)));
        }
      } finally {
        CompanyContextHolder.clear();
      }
    };
  }

  private Company createCompany(CompanyRepository companyRepository) {
    return companyRepository
        .findByCodeIgnoreCase("MOCK")
        .map(
            existing -> {
              if (existing.getName() == null) {
                existing.setName("Mock Training Co");
              }
              if (existing.getTimezone() == null) {
                existing.setTimezone("UTC");
              }
              if (existing.getDefaultGstRate() == null) {
                existing.setDefaultGstRate(new BigDecimal("18"));
              }
              if (existing.getBaseCurrency() == null) {
                existing.setBaseCurrency("INR");
              }
              if (!StringUtils.hasText(existing.getStateCode())) {
                existing.setStateCode(DEFAULT_STATE_CODE);
              }
              return companyRepository.save(existing);
            })
        .orElseGet(
            () -> {
              Company c = new Company();
              c.setCode("MOCK");
              c.setName("Mock Training Co");
              c.setTimezone("UTC");
              c.setDefaultGstRate(new BigDecimal("18"));
              c.setBaseCurrency("INR");
              c.setStateCode(DEFAULT_STATE_CODE);
              return companyRepository.save(c);
            });
  }

  private Map<String, Account> seedAccounts(
      Company company, AccountRepository accountRepository, CompanyRepository companyRepository) {
    Map<String, Account> map = new HashMap<>();
    map.put("CASH", ensureAccount(company, "CASH", "Cash", AccountType.ASSET, accountRepository));
    map.put(
        "AR",
        ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET, accountRepository));
    map.put(
        "AP",
        ensureAccount(company, "AP", "Accounts Payable", AccountType.LIABILITY, accountRepository));
    map.put(
        "INV", ensureAccount(company, "INV", "Inventory", AccountType.ASSET, accountRepository));
    map.put(
        "COGS",
        ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS, accountRepository));
    map.put(
        "REV", ensureAccount(company, "REV", "Revenue", AccountType.REVENUE, accountRepository));
    map.put(
        "GST_IN",
        ensureAccount(company, "GST-IN", "GST Input Tax", AccountType.ASSET, accountRepository));
    map.put(
        "GST_OUT",
        ensureAccount(
            company, "GST-OUT", "GST Output Tax", AccountType.LIABILITY, accountRepository));
    map.put(
        "GST_PAY",
        ensureAccount(company, "GST-PAY", "GST Payable", AccountType.LIABILITY, accountRepository));
    map.put(
        "DISC",
        ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE, accountRepository));
    map.put(
        "EXP", ensureAccount(company, "EXP", "Expenses", AccountType.EXPENSE, accountRepository));
    map.put(
        "LABOR",
        ensureAccount(company, "LABOR", "Direct Labor", AccountType.EXPENSE, accountRepository));
    map.put(
        "OVERHEAD",
        ensureAccount(
            company, "OVERHEAD", "Manufacturing Overhead", AccountType.EXPENSE, accountRepository));
    // WIP accounts for production - mixing and packing stages
    map.put(
        "WIP_MIX",
        ensureAccount(
            company, "1170", "Work in Progress - Mixing", AccountType.ASSET, accountRepository));
    map.put(
        "WIP_PACK",
        ensureAccount(
            company, "1180", "Work in Progress - Packing", AccountType.ASSET, accountRepository));

    company.setGstInputTaxAccountId(map.get("GST_IN").getId());
    company.setGstOutputTaxAccountId(map.get("GST_OUT").getId());
    company.setGstPayableAccountId(map.get("GST_PAY").getId());
    accountRepository.saveAll(map.values());
    companyRepository.save(company);
    return map;
  }

  private Account ensureAccount(
      Company company, String code, String name, AccountType type, AccountRepository repo) {
    return repo.findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(name);
              a.setType(type);
              a.setBalance(BigDecimal.ZERO);
              return repo.save(a);
            });
  }

  private UserAccount seedRolesAndUsers(
      RoleRepository roleRepository,
      UserAccountRepository userRepository,
      PasswordEncoder encoder,
      Company company,
      String adminEmail,
      String adminPassword) {
    Role admin =
        roleRepository
            .findByName("ROLE_ADMIN")
            .orElseGet(
                () -> {
                  Role r = new Role();
                  r.setName("ROLE_ADMIN");
                  r.setDescription("Administrator");
                  return roleRepository.save(r);
                });
    Role accounting =
        roleRepository
            .findByName("ROLE_ACCOUNTING")
            .orElseGet(
                () -> {
                  Role r = new Role();
                  r.setName("ROLE_ACCOUNTING");
                  r.setDescription("Accounting");
                  return roleRepository.save(r);
                });
    Role sales =
        roleRepository
            .findByName("ROLE_SALES")
            .orElseGet(
                () -> {
                  Role r = new Role();
                  r.setName("ROLE_SALES");
                  r.setDescription("Sales");
                  return roleRepository.save(r);
                });

    if (!StringUtils.hasText(adminEmail)) {
      log.info("Mock admin seed skipped: set erp.seed.mock-admin.email to enable bootstrap");
      return null;
    }
    String normalizedEmail = adminEmail.trim().toLowerCase(Locale.ROOT);
    UserAccount existingAdmin =
        userRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(normalizedEmail, company.getCode())
            .orElse(null);
    if (existingAdmin == null) {
      if (!StringUtils.hasText(adminPassword)) {
        throw new IllegalStateException(
            "erp.seed.mock-admin.password is required when bootstrap mock-admin user does not"
                + " exist");
      }
      UserAccount user =
          new UserAccount(
              normalizedEmail,
              company.getCode(),
              encoder.encode(adminPassword.trim()),
              "Mock Admin");
      user.setMustChangePassword(true);
      user.setCompany(company);
      user.addRole(admin);
      user.addRole(accounting);
      user.addRole(sales);
      return userRepository.save(user);
    }
    existingAdmin.setAuthScopeCode(company.getCode());
    existingAdmin.setCompany(company);
    existingAdmin.addRole(admin);
    existingAdmin.addRole(accounting);
    existingAdmin.addRole(sales);
    return userRepository.save(existingAdmin);
  }

  private void attachMainAdmin(
      CompanyRepository companyRepository, Company company, UserAccount adminUser) {
    SeedCompanyAdminSupport.attachMainAdmin(companyRepository, company, adminUser);
  }

  private void seedReadyToConfirmOrder(
      SalesOrderCrudService salesOrderCrudService,
      SalesFulfillmentService salesFulfillmentService,
      Dealer dealer) {
    if (dealer == null || dealer.getId() == null) {
      return;
    }
    SalesOrderDto order =
        salesOrderCrudService.createOrder(
            new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("236.00"),
                "INR",
                "Mock ready-to-confirm UAT order",
                List.of(
                    new SalesOrderItemRequest(
                        "FG-GST",
                        "Mock ready-to-confirm line",
                        new BigDecimal("10"),
                        new BigDecimal("20.00"),
                        new BigDecimal("18.00"))),
                "ORDER_TOTAL",
                new BigDecimal("18.00"),
                Boolean.FALSE,
                READY_CONFIRM_ORDER_IDEMPOTENCY_KEY,
                "CREDIT"));
    if (order != null && order.id() != null && shouldSeedReservation(order.status())) {
      salesFulfillmentService.reserveForOrder(order.id());
      log.info(
          "Mock ready-to-confirm sales order seeded for UAT: orderId={} orderNumber={}",
          order.id(),
          order.orderNumber());
    }
  }

  private boolean shouldSeedReservation(String orderStatus) {
    if (!StringUtils.hasText(orderStatus)) {
      return true;
    }
    return switch (orderStatus.trim().toUpperCase(Locale.ROOT)) {
      case "PENDING", "PENDING_STOCK", "PENDING_PRODUCTION" -> true;
      default -> false;
    };
  }

  private void seedPendingApprovalFixtures(
      Company company,
      UserAccount seededAdmin,
      Dealer dealer,
      DealerRepository dealerRepository,
      ExportRequestRepository exportRequestRepository,
      SupportTicketRepository supportTicketRepository,
      CreditRequestRepository creditRequestRepository) {
    if (company == null || company.getId() == null) {
      return;
    }
    Dealer approvalDealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "VALID-DEALER")
            .orElse(dealer);
    seedPendingExportRequest(company, seededAdmin, exportRequestRepository);
    seedPendingSupportTicket(company, approvalDealer, supportTicketRepository);
    seedPendingCreditRequest(company, approvalDealer, creditRequestRepository);
  }

  private void seedPendingExportRequest(
      Company company,
      UserAccount seededAdmin,
      ExportRequestRepository exportRequestRepository) {
    if (seededAdmin == null || seededAdmin.getId() == null) {
      return;
    }
    boolean exists =
        exportRequestRepository.findByCompanyAndStatusOrderByCreatedAtAsc(company, ExportApprovalStatus.PENDING)
            .stream()
            .anyMatch(
                request ->
                    seededAdmin.getId().equals(request.getUserId())
                        && APPROVAL_EXPORT_REPORT_TYPE.equalsIgnoreCase(request.getReportType())
                        && APPROVAL_EXPORT_PARAMETERS.equals(request.getParameters()));
    if (exists) {
      return;
    }

    ExportRequest request = new ExportRequest();
    request.setCompany(company);
    request.setUserId(seededAdmin.getId());
    request.setReportType(APPROVAL_EXPORT_REPORT_TYPE);
    request.setParameters(APPROVAL_EXPORT_PARAMETERS);
    request.setStatus(ExportApprovalStatus.PENDING);
    exportRequestRepository.save(request);
  }

  private void seedPendingSupportTicket(
      Company company, Dealer dealer, SupportTicketRepository supportTicketRepository) {
    if (dealer == null
        || dealer.getPortalUser() == null
        || dealer.getPortalUser().getId() == null
        || company == null) {
      return;
    }
    boolean exists =
        supportTicketRepository.findByCompanyAndUserIdOrderByCreatedAtDesc(
                company, dealer.getPortalUser().getId())
            .stream()
            .anyMatch(ticket -> APPROVAL_SUPPORT_SUBJECT.equalsIgnoreCase(ticket.getSubject()));
    if (exists) {
      return;
    }

    SupportTicket ticket = new SupportTicket();
    ticket.setCompany(company);
    ticket.setUserId(dealer.getPortalUser().getId());
    ticket.setCategory(SupportTicketCategory.SUPPORT);
    ticket.setSubject(APPROVAL_SUPPORT_SUBJECT);
    ticket.setDescription(APPROVAL_SUPPORT_DESCRIPTION);
    supportTicketRepository.save(ticket);
  }

  private void seedPendingCreditRequest(
      Company company, Dealer dealer, CreditRequestRepository creditRequestRepository) {
    if (company == null || dealer == null || dealer.getId() == null) {
      return;
    }
    boolean exists =
        creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company).stream()
            .anyMatch(
                request ->
                    request.getDealer() != null
                        && dealer.getId().equals(request.getDealer().getId())
                        && APPROVAL_CREDIT_REASON.equals(request.getReason()));
    if (exists) {
      return;
    }

    CreditRequest request = new CreditRequest();
    request.setCompany(company);
    request.setDealer(dealer);
    request.setAmountRequested(new BigDecimal("75000.00"));
    request.setStatus("PENDING");
    request.setReason(APPROVAL_CREDIT_REASON);
    if (dealer.getPortalUser() != null) {
      request.setRequesterUserId(dealer.getPortalUser().getId());
      request.setRequesterEmail(dealer.getPortalUser().getEmail());
    }
    creditRequestRepository.save(request);
  }

  private void seedProcureToPayFixture(
      Company company,
      Supplier supplier,
      RawMaterialRepository rawMaterialRepository,
      PurchasingService purchasingService,
      PurchaseOrderRepository purchaseOrderRepository,
      GoodsReceiptRepository goodsReceiptRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    if (company == null || supplier == null || supplier.getId() == null) {
      return;
    }

    RawMaterial rawMaterial =
        rawMaterialRepository
            .findByCompanyAndSku(company, "RM-RESIN")
            .orElse(null);
    if (rawMaterial == null || rawMaterial.getId() == null) {
      return;
    }

    if (rawMaterialPurchaseRepository
        .findByCompanyAndInvoiceNumberIgnoreCase(company, P2P_INVOICE_NUMBER)
        .isPresent()) {
      return;
    }

    PurchaseOrder purchaseOrder =
        purchaseOrderRepository.findByCompanyAndOrderNumberIgnoreCase(company, P2P_ORDER_NUMBER).orElse(null);
    if (purchaseOrder == null) {
      PurchaseOrderResponse created =
          purchasingService.createPurchaseOrder(
              new PurchaseOrderRequest(
                  supplier.getId(),
                  P2P_ORDER_NUMBER,
                  P2P_ORDER_DATE,
                  "Seeded PO for MOCK P2P validation chain",
                  List.of(
                      new PurchaseOrderLineRequest(
                          rawMaterial.getId(),
                          new BigDecimal("40.0000"),
                          "UNIT",
                          new BigDecimal("5.50"),
                          "Validation seeded PO line"))));
      purchaseOrder =
          purchaseOrderRepository
              .findByCompanyAndId(company, created.id())
              .orElseThrow();
    }

    if ("DRAFT".equalsIgnoreCase(purchaseOrder.getStatus())) {
      purchasingService.approvePurchaseOrder(purchaseOrder.getId());
      purchaseOrder =
          purchaseOrderRepository.findByCompanyAndId(company, purchaseOrder.getId()).orElseThrow();
    }

    GoodsReceipt goodsReceipt =
        goodsReceiptRepository.findByCompanyAndReceiptNumberIgnoreCase(company, P2P_RECEIPT_NUMBER).orElse(null);
    if (goodsReceipt == null) {
      GoodsReceiptResponse created =
          purchasingService.createGoodsReceipt(
              new GoodsReceiptRequest(
                  purchaseOrder.getId(),
                  P2P_RECEIPT_NUMBER,
                  P2P_RECEIPT_DATE,
                  "Seeded GRN for MOCK P2P validation chain",
                  P2P_RECEIPT_IDEMPOTENCY_KEY,
                  List.of(
                      new GoodsReceiptLineRequest(
                          rawMaterial.getId(),
                          P2P_BATCH_CODE,
                          new BigDecimal("40.0000"),
                          "UNIT",
                          new BigDecimal("5.50"),
                          "Validation seeded GRN line"))));
      goodsReceipt =
          goodsReceiptRepository.findByCompanyAndId(company, created.id()).orElseThrow();
    }

    if (rawMaterialPurchaseRepository
        .findByCompanyAndInvoiceNumberIgnoreCase(company, P2P_INVOICE_NUMBER)
        .isPresent()) {
      return;
    }

    purchasingService.createPurchase(
        new RawMaterialPurchaseRequest(
            supplier.getId(),
            P2P_INVOICE_NUMBER,
            P2P_INVOICE_DATE,
            "Seeded purchase invoice for MOCK P2P validation chain",
            purchaseOrder.getId(),
            goodsReceipt.getId(),
            BigDecimal.ZERO,
            List.of(
                new RawMaterialPurchaseLineRequest(
                    rawMaterial.getId(),
                    null,
                    new BigDecimal("40.0000"),
                    "UNIT",
                    new BigDecimal("5.50"),
                    null,
                    null,
                    "Validation seeded purchase invoice line"))));
  }

  private Dealer seedDealer(
      Company company,
      DealerRepository dealerRepository,
      Account ar) {
    Dealer dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "GST-DEALER")
            .orElseGet(
                () -> {
                  Dealer d = new Dealer();
                  d.setCompany(company);
                  d.setCode("GST-DEALER");
                  d.setName("Mock GST Dealer");
                  return d;
                });
    dealer.setReceivableAccount(ar);
    dealer.setCreditLimit(new BigDecimal("500000"));
    dealer.setOutstandingBalance(BigDecimal.ZERO);
    dealer.setEmail("dealer@mock.com");
    dealer.setStatus("ACTIVE");
    dealer.setStateCode(resolveStateCode(company.getStateCode(), dealer.getStateCode()));
    dealer.setGstRegistrationType(GstRegistrationType.REGULAR);
    return dealerRepository.save(dealer);
  }

  private Supplier seedSupplier(
      Company company, SupplierRepository supplierRepository, Account ap) {
    Supplier supplier =
        supplierRepository
            .findByCompanyAndCodeIgnoreCase(company, "SUP-RAW")
            .orElseGet(
                () -> {
                  Supplier s = new Supplier();
                  s.setCompany(company);
                  s.setCode("SUP-RAW");
                  s.setName("Mock Raw Supplier");
                  return s;
                });
    supplier.setPayableAccount(ap);
    supplier.setCreditLimit(new BigDecimal("500000"));
    supplier.setEmail("supplier@mock.com");
    supplier.setStatus("ACTIVE");
    supplier.setStateCode(resolveStateCode(company.getStateCode(), supplier.getStateCode()));
    supplier.setGstRegistrationType(GstRegistrationType.REGULAR);
    return supplierRepository.save(supplier);
  }

  private String resolveStateCode(String primaryStateCode, String existingStateCode) {
    if (StringUtils.hasText(primaryStateCode)) {
      return primaryStateCode.trim().toUpperCase(Locale.ROOT);
    }
    if (StringUtils.hasText(existingStateCode)) {
      return existingStateCode.trim().toUpperCase(Locale.ROOT);
    }
    return DEFAULT_STATE_CODE;
  }

  private ProductionBrand seedBrand(Company company, ProductionBrandRepository brandRepository) {
    return brandRepository
        .findByCompanyAndCodeIgnoreCase(company, "MOCK-BRAND")
        .orElseGet(
            () -> {
              ProductionBrand brand = new ProductionBrand();
              brand.setCompany(company);
              brand.setCode("MOCK-BRAND");
              brand.setName("Mock Brand");
              return brandRepository.save(brand);
            });
  }

  private FinishedGood seedFinishedGood(
      Company company,
      FinishedGoodRepository finishedGoodRepository,
      ProductionProductRepository productRepository,
      Map<String, Account> accounts,
      ProductionBrand brand,
      String sku,
      String costingMethod,
      Account wipAccount) {
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, sku)
            .orElseGet(
                () -> {
                  FinishedGood f = new FinishedGood();
                  f.setCompany(company);
                  f.setProductCode(sku);
                  f.setName("Mock " + sku);
                  f.setUnit("UNIT");
                  f.setCostingMethod(costingMethod);
                  f.setValuationAccountId(accounts.get("INV").getId());
                  f.setCogsAccountId(accounts.get("COGS").getId());
                  f.setRevenueAccountId(accounts.get("REV").getId());
                  f.setDiscountAccountId(accounts.get("DISC").getId());
                  f.setTaxAccountId(accounts.get("GST_OUT").getId());
                  return finishedGoodRepository.save(f);
                });

    productRepository
        .findByCompanyAndSkuCode(company, sku)
        .orElseGet(
            () -> {
              ProductionProduct product = new ProductionProduct();
              product.setCompany(company);
              product.setBrand(brand);
              product.setProductName("Product " + sku);
              product.setCategory("FINISHED_GOOD");
              product.setUnitOfMeasure("UNIT");
              product.setSkuCode(sku);
              product.setBasePrice(new BigDecimal("20.00"));
              product.setGstRate(new BigDecimal("18.00"));
              product.setMinDiscountPercent(BigDecimal.ZERO);
              product.setMinSellingPrice(BigDecimal.ZERO);
              // Set metadata for production log journal entries
              Map<String, Object> metadata = new HashMap<>();
              // WIP account for production
              metadata.put("wipAccountId", wipAccount.getId());
              // Semi-finished goods use the same INV account
              metadata.put("semiFinishedAccountId", accounts.get("INV").getId());
              // Finished good account references (for semi-finished FG creation)
              metadata.put("fgValuationAccountId", accounts.get("INV").getId());
              metadata.put("fgCogsAccountId", accounts.get("COGS").getId());
              metadata.put("fgRevenueAccountId", accounts.get("REV").getId());
              metadata.put("fgDiscountAccountId", accounts.get("DISC").getId());
              metadata.put("fgTaxAccountId", accounts.get("GST_OUT").getId());
              metadata.put("laborAppliedAccountId", accounts.get("LABOR").getId());
              metadata.put("overheadAppliedAccountId", accounts.get("OVERHEAD").getId());
              product.setMetadata(metadata);
              return productRepository.save(product);
            });
    return fg;
  }

  private void seedBatches(
      Company company,
      FinishedGoodBatchRepository batchRepository,
      FinishedGoodRepository finishedGoodRepository,
      FinishedGood fgFifo,
      FinishedGood fgLifo,
      FinishedGood fgKit) {
    createBatch(
        batchRepository,
        finishedGoodRepository,
        fgFifo,
        "B-FIFO-1",
        new BigDecimal("80"),
        new BigDecimal("10.00"),
        CompanyTime.now(company).minusSeconds(86400));
    createBatch(
        batchRepository,
        finishedGoodRepository,
        fgFifo,
        "B-FIFO-2",
        new BigDecimal("120"),
        new BigDecimal("12.00"),
        CompanyTime.now(company).minusSeconds(3600));
    createBatch(
        batchRepository,
        finishedGoodRepository,
        fgLifo,
        "B-LIFO-1",
        new BigDecimal("60"),
        new BigDecimal("9.50"),
        CompanyTime.now(company).minusSeconds(7200));
    createBatch(
        batchRepository,
        finishedGoodRepository,
        fgLifo,
        "B-LIFO-2",
        new BigDecimal("140"),
        new BigDecimal("11.25"),
        CompanyTime.now(company));
    createBatch(
        batchRepository,
        finishedGoodRepository,
        fgKit,
        "B-KIT-1",
        new BigDecimal("200"),
        new BigDecimal("8.75"),
        CompanyTime.now(company).minusSeconds(5400));
  }

  private void createBatch(
      FinishedGoodBatchRepository batchRepository,
      FinishedGoodRepository finishedGoodRepository,
      FinishedGood fg,
      String batchCode,
      BigDecimal qty,
      BigDecimal unitCost,
      Instant manufacturedAt) {
    // Skip if batch already exists for this FG
    if (fg.getId() != null) {
      boolean exists =
          batchRepository.findByFinishedGood_ValuationAccountId(fg.getValuationAccountId()).stream()
              .anyMatch(
                  b ->
                      b.getFinishedGood().getId().equals(fg.getId())
                          && batchCode.equalsIgnoreCase(b.getBatchCode()));
      if (exists) {
        return;
      }
    }
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(fg);
    batch.setBatchCode(batchCode);
    batch.setQuantityTotal(qty);
    batch.setQuantityAvailable(qty);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(manufacturedAt);
    batchRepository.save(batch);
    FinishedGood managed = finishedGoodRepository.findById(fg.getId()).orElse(fg);
    managed.setCurrentStock(managed.getCurrentStock().add(qty));
    finishedGoodRepository.save(managed);
  }

  private void seedRawMaterials(
      Company company,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      Map<String, Account> accounts) {
    RawMaterial rm1 =
        ensureRawMaterial(
            company, rawMaterialRepository, "RM-RESIN", "Resin Base", accounts.get("INV").getId());
    RawMaterial rm2 =
        ensureRawMaterial(
            company, rawMaterialRepository, "RM-PIG", "Pigment Red", accounts.get("INV").getId());
    RawMaterial rm3 =
        ensureRawMaterial(
            company, rawMaterialRepository, "RM-CAN", "Metal Can", accounts.get("INV").getId());

    seedRawBatch(
        rawMaterialBatchRepository,
        rawMaterialRepository,
        rm1,
        "RM-RESIN-B1",
        new BigDecimal("500"),
        new BigDecimal("5.50"));
    seedRawBatch(
        rawMaterialBatchRepository,
        rawMaterialRepository,
        rm2,
        "RM-PIG-B1",
        new BigDecimal("200"),
        new BigDecimal("3.25"));
    seedRawBatch(
        rawMaterialBatchRepository,
        rawMaterialRepository,
        rm3,
        "RM-CAN-B1",
        new BigDecimal("1000"),
        new BigDecimal("1.20"));
  }

  private RawMaterial ensureRawMaterial(
      Company company,
      RawMaterialRepository repo,
      String sku,
      String name,
      Long inventoryAccountId) {
    return repo.findByCompanyAndSku(company, sku)
        .orElseGet(
            () -> {
              RawMaterial rm = new RawMaterial();
              rm.setCompany(company);
              rm.setSku(sku);
              rm.setName(name);
              rm.setUnitType("UNIT");
              rm.setInventoryAccountId(inventoryAccountId);
              rm.setReorderLevel(new BigDecimal("50"));
              rm.setMinStock(new BigDecimal("50"));
              rm.setMaxStock(new BigDecimal("5000"));
              return repo.save(rm);
            });
  }

  private void seedRawBatch(
      RawMaterialBatchRepository batchRepository,
      RawMaterialRepository rawMaterialRepository,
      RawMaterial rawMaterial,
      String batchCode,
      BigDecimal qty,
      BigDecimal cost) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(rawMaterial);
    batch.setBatchCode(batchCode);
    batch.setQuantity(qty);
    batch.setUnit("UNIT");
    batch.setCostPerUnit(cost);
    batchRepository.save(batch);
    rawMaterial.setCurrentStock(rawMaterial.getCurrentStock().add(qty));
    rawMaterialRepository.save(rawMaterial);
  }

  private void seedSalesPurchaseAndCogs(
      AccountingService accountingService,
      Company company,
      Dealer dealer,
      Supplier supplier,
      Map<String, Account> accounts) {
    // Sale
    BigDecimal saleTotal = new BigDecimal("1180.00");
    BigDecimal tax = new BigDecimal("180.00");
    BigDecimal revenue = saleTotal.subtract(tax);
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "MOCK-SALE-1",
            CompanyTime.today(company).minusDays(10),
            "Mock GST sale",
            dealer.getId(),
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("AR").getId(), "AR", saleTotal, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("REV").getId(), "Revenue", BigDecimal.ZERO, revenue),
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("GST_OUT").getId(), "Output tax", BigDecimal.ZERO, tax))));

    // Purchase
    BigDecimal purchaseTotal = new BigDecimal("590.00");
    BigDecimal purchaseTax = new BigDecimal("90.00");
    BigDecimal inventory = purchaseTotal.subtract(purchaseTax);
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "MOCK-PO-1",
            CompanyTime.today(company).minusDays(15),
            "Mock purchase",
            null,
            supplier.getId(),
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("INV").getId(), "Inventory", inventory, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("GST_IN").getId(), "Input tax", purchaseTax, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("AP").getId(), "AP", BigDecimal.ZERO, purchaseTotal))));

    // COGS for sale
    BigDecimal cogs = new BigDecimal("320.00");
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "MOCK-COGS-1",
            CompanyTime.today(company).minusDays(10),
            "COGS for MOCK-SALE-1",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("COGS").getId(), "COGS", cogs, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    accounts.get("INV").getId(), "Inventory", BigDecimal.ZERO, cogs))));
  }

  private void postSimpleSale(
      AccountingService accountingService,
      Company company,
      Dealer dealer,
      Account revenue,
      Account gstOut,
      Account ar,
      BigDecimal amount) {
    BigDecimal tax =
        amount.multiply(new BigDecimal("0.18")).setScale(2, java.math.RoundingMode.HALF_UP);
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "MOCK-SALE-" + UUID.randomUUID(),
            CompanyTime.today(company),
            "Mock sale",
            dealer.getId(),
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    ar.getId(), "AR", amount.add(tax), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    revenue.getId(), "Revenue", BigDecimal.ZERO, amount),
                new JournalEntryRequest.JournalLineRequest(
                    gstOut.getId(), "GST Output", BigDecimal.ZERO, tax))));
  }
}
