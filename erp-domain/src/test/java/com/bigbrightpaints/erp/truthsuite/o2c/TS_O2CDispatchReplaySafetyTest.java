package com.bigbrightpaints.erp.truthsuite.o2c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("reconciliation")
@TestPropertySource(properties = "erp.auto-approval.enabled=false")
class TS_O2CDispatchReplaySafetyTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private SalesService salesService;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void confirmDispatchReplay_returnsExistingInvoiceWithoutCreatingDuplicateInvoices() {
    DispatchFixture fixture = bootstrapDispatchFixture("TS-RPLY-INV");

    DispatchConfirmResponse first = salesService.confirmDispatch(fixture.salesRequest());
    PackagingSlip dispatchedSlip =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    int invoiceCountBeforeReplay =
        invoiceRepository
            .findAllByCompanyAndSalesOrderId(fixture.company(), fixture.order().getId())
            .size();
    long dispatchNoteMatchesBeforeReplay =
        countInvoicesForSlip(dispatchNoteFor(dispatchedSlip), fixture.company(), fixture.order());

    DispatchConfirmResponse replay = salesService.confirmDispatch(fixture.salesRequest());

    PackagingSlip replayedSlip =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    List<Invoice> invoicesForOrder =
        invoiceRepository.findAllByCompanyAndSalesOrderId(
            fixture.company(), fixture.order().getId());

    assertThat(first.finalInvoiceId()).isNotNull();
    assertThat(replay.finalInvoiceId()).isEqualTo(first.finalInvoiceId());
    assertThat(replay.arJournalEntryId()).isEqualTo(first.arJournalEntryId());
    assertThat(replay.cogsPostings()).isEmpty();
    assertThat(replayedSlip.getInvoiceId()).isEqualTo(first.finalInvoiceId());
    assertThat(invoicesForOrder).hasSize(invoiceCountBeforeReplay);
    assertThat(
            countInvoicesForSlip(dispatchNoteFor(replayedSlip), fixture.company(), fixture.order()))
        .isEqualTo(dispatchNoteMatchesBeforeReplay)
        .isEqualTo(1);

    CoderedDbAssertions.assertOneInvoicePerSlip(
        packagingSlipRepository, invoiceRepository, fixture.company(), replayedSlip.getId());
  }

  @Test
  void confirmDispatchReplay_reusesExistingArAndCogsJournalsWithoutDuplicateReferences() {
    DispatchFixture fixture = bootstrapDispatchFixture("TS-RPLY-JRN");

    DispatchConfirmResponse first = salesService.confirmDispatch(fixture.salesRequest());
    PackagingSlip dispatchedSlip =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    JournalEntry arJournal =
        journalEntryRepository.findById(first.arJournalEntryId()).orElseThrow();
    JournalEntry cogsJournal =
        journalEntryRepository.findById(dispatchedSlip.getCogsJournalEntryId()).orElseThrow();
    long arReferenceCountBeforeReplay =
        countJournalEntries(fixture.company(), arJournal.getReferenceNumber());
    long cogsReferenceCountBeforeReplay =
        countJournalEntries(fixture.company(), cogsJournal.getReferenceNumber());
    int totalJournalRowsBeforeReplay =
        journalEntryRepository.findByCompanyOrderByEntryDateDesc(fixture.company()).size();

    DispatchConfirmResponse replay = salesService.confirmDispatch(fixture.salesRequest());
    PackagingSlip replayedSlip =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();

    assertThat(replay.finalInvoiceId()).isEqualTo(first.finalInvoiceId());
    assertThat(replay.arJournalEntryId()).isEqualTo(first.arJournalEntryId());
    assertThat(replayedSlip.getJournalEntryId()).isEqualTo(first.arJournalEntryId());
    assertThat(replayedSlip.getCogsJournalEntryId())
        .isEqualTo(dispatchedSlip.getCogsJournalEntryId());
    assertThat(countJournalEntries(fixture.company(), arJournal.getReferenceNumber()))
        .as(
            "uk_journal_company_reference keeps a single AR journal row for %s",
            arJournal.getReferenceNumber())
        .isEqualTo(arReferenceCountBeforeReplay)
        .isEqualTo(1);
    assertThat(countJournalEntries(fixture.company(), cogsJournal.getReferenceNumber()))
        .as(
            "uk_journal_company_reference keeps a single COGS journal row for %s",
            cogsJournal.getReferenceNumber())
        .isEqualTo(cogsReferenceCountBeforeReplay)
        .isEqualTo(1);
    assertThat(journalEntryRepository.findByCompanyOrderByEntryDateDesc(fixture.company()))
        .hasSize(totalJournalRowsBeforeReplay);

    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, replayedSlip.getJournalEntryId());
    CoderedDbAssertions.assertBalancedJournal(
        journalEntryRepository, replayedSlip.getCogsJournalEntryId());
  }

  @Test
  void finishedGoodsReplay_shortCircuitsWithoutCreatingDuplicateDispatchMovements() {
    DispatchFixture fixture = bootstrapDispatchFixture("TS-RPLY-MOV");

    finishedGoodsService.confirmDispatch(fixture.inventoryRequest(), "truthsuite-user");
    FinishedGood afterFirstDispatch =
        finishedGoodRepository.findById(fixture.finishedGood().getId()).orElseThrow();
    List<InventoryMovement> dispatchMovementsBeforeReplay =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                fixture.company(), fixture.slip().getId(), "DISPATCH");

    var replay =
        finishedGoodsService.confirmDispatch(fixture.inventoryRequest(), "truthsuite-user");

    FinishedGood afterReplay =
        finishedGoodRepository.findById(fixture.finishedGood().getId()).orElseThrow();
    List<InventoryMovement> dispatchMovementsAfterReplay =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                fixture.company(), fixture.slip().getId(), "DISPATCH");

    assertThat(replay.status()).isEqualTo("DISPATCHED");
    assertThat(dispatchMovementsAfterReplay)
        .extracting(InventoryMovement::getId)
        .containsExactlyElementsOf(
            dispatchMovementsBeforeReplay.stream().map(InventoryMovement::getId).toList());
    assertThat(afterReplay.getCurrentStock())
        .isEqualByComparingTo(afterFirstDispatch.getCurrentStock());
    assertThat(afterReplay.getReservedStock())
        .isEqualByComparingTo(afterFirstDispatch.getReservedStock());

    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, fixture.company().getId());
  }

  @Test
  void confirmDispatchReplay_failsClosedWhenMultipleInvoicesMatchSlip() {
    DispatchFixture fixture = bootstrapDispatchFixture("TS-RPLY-AMB");

    DispatchConfirmResponse first = salesService.confirmDispatch(fixture.salesRequest());
    PackagingSlip dispatchedSlip =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    long invoiceCountBeforeAmbiguousReplay =
        invoiceRepository
            .findAllByCompanyAndSalesOrderId(fixture.company(), fixture.order().getId())
            .size();

    Invoice ambiguousInvoice = new Invoice();
    ambiguousInvoice.setCompany(fixture.company());
    ambiguousInvoice.setDealer(fixture.dealer());
    ambiguousInvoice.setSalesOrder(fixture.order());
    ambiguousInvoice.setInvoiceNumber("AMB-" + shortId());
    ambiguousInvoice.setStatus("ISSUED");
    ambiguousInvoice.setSubtotal(new BigDecimal("123.40"));
    ambiguousInvoice.setTaxTotal(BigDecimal.ZERO);
    ambiguousInvoice.setTotalAmount(new BigDecimal("123.40"));
    ambiguousInvoice.setOutstandingAmount(new BigDecimal("123.40"));
    ambiguousInvoice.setCurrency("INR");
    ambiguousInvoice.setIssueDate(LocalDate.now());
    ambiguousInvoice.setNotes(dispatchNoteFor(dispatchedSlip));
    ambiguousInvoice = invoiceRepository.save(ambiguousInvoice);

    dispatchedSlip.setInvoiceId(null);
    packagingSlipRepository.save(dispatchedSlip);

    assertThatThrownBy(() -> salesService.confirmDispatch(fixture.salesRequest()))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("manual reconciliation required")
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getDetails())
                  .containsEntry("packingSlipId", dispatchedSlip.getId())
                  .containsEntry("slipNumber", dispatchedSlip.getSlipNumber());
            });

    List<Invoice> invoicesForOrder =
        invoiceRepository.findAllByCompanyAndSalesOrderId(
            fixture.company(), fixture.order().getId());
    assertThat(invoicesForOrder).hasSize((int) invoiceCountBeforeAmbiguousReplay + 1);
    assertThat(invoicesForOrder)
        .extracting(Invoice::getId)
        .doesNotContainNull()
        .contains(first.finalInvoiceId(), ambiguousInvoice.getId());
  }

  private DispatchFixture bootstrapDispatchFixture(String prefix) {
    String companyCode = prefix + "-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"));
    FinishedGood finishedGood =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), BigDecimal.ZERO);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            finishedGood.getId(),
            "BATCH-" + shortId(),
            new BigDecimal("50"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));

    SalesOrder order =
        createOrder(
            company,
            dealer,
            finishedGood.getProductCode(),
            new BigDecimal("10"),
            new BigDecimal("12.34"));
    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();

    DispatchConfirmRequest salesRequest =
        new DispatchConfirmRequest(
            slip.getId(),
            order.getId(),
            slip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmRequest.DispatchLine(
                            line.getId(),
                            null,
                            line.getOrderedQuantity() != null
                                ? line.getOrderedQuantity()
                                : line.getQuantity(),
                            null,
                            null,
                            null,
                            null,
                            null))
                .toList(),
            "truthsuite replay characterization",
            "truthsuite-user",
            false,
            null,
            null);

    DispatchConfirmationRequest inventoryRequest =
        new DispatchConfirmationRequest(
            slip.getId(),
            slip.getLines().stream()
                .map(
                    line ->
                        new DispatchConfirmationRequest.LineConfirmation(
                            line.getId(),
                            line.getOrderedQuantity() != null
                                ? line.getOrderedQuantity()
                                : line.getQuantity(),
                            "truthsuite replay characterization"))
                .toList(),
            "truthsuite replay characterization",
            "truthsuite-user",
            null);

    return new DispatchFixture(
        company, dealer, finishedGood, order, slip, salesRequest, inventoryRequest);
  }

  private Company bootstrapCompany(String companyCode, String timezone) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone(timezone);
    company.setBaseCurrency("INR");
    companyRepository.save(company);
    return company;
  }

  private Map<String, Account> ensureCoreAccounts(Company company) {
    Account ar = ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET);
    Account inv = ensureAccount(company, "INV", "Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS", "COGS", AccountType.COGS);
    Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
    Account gstOut = ensureAccount(company, "GST-OUT", "GST Output", AccountType.LIABILITY);
    Account gstIn = ensureAccount(company, "GST-IN", "GST Input", AccountType.ASSET);

    updateCompanyDefaults(company, inv, cogs, rev, disc, gstOut, gstIn);

    return Map.of(
        "AR", ar,
        "INV", inv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut,
        "GST_IN", gstIn);
  }

  private void updateCompanyDefaults(
      Company company,
      Account inv,
      Account cogs,
      Account rev,
      Account disc,
      Account gstOut,
      Account gstIn) {
    if (company == null || company.getId() == null) {
      return;
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      Company fresh = companyRepository.findById(company.getId()).orElseThrow();
      boolean updated = false;
      if (fresh.getDefaultInventoryAccountId() == null) {
        fresh.setDefaultInventoryAccountId(inv.getId());
        updated = true;
      }
      if (fresh.getDefaultCogsAccountId() == null) {
        fresh.setDefaultCogsAccountId(cogs.getId());
        updated = true;
      }
      if (fresh.getDefaultRevenueAccountId() == null) {
        fresh.setDefaultRevenueAccountId(rev.getId());
        updated = true;
      }
      if (fresh.getDefaultDiscountAccountId() == null) {
        fresh.setDefaultDiscountAccountId(disc.getId());
        updated = true;
      }
      if (fresh.getDefaultTaxAccountId() == null) {
        fresh.setDefaultTaxAccountId(gstOut.getId());
        updated = true;
      }
      if (fresh.getGstInputTaxAccountId() == null) {
        fresh.setGstInputTaxAccountId(gstIn.getId());
        updated = true;
      }
      if (fresh.getGstOutputTaxAccountId() == null) {
        fresh.setGstOutputTaxAccountId(gstOut.getId());
        updated = true;
      }
      if (fresh.getGstPayableAccountId() == null) {
        fresh.setGstPayableAccountId(gstOut.getId());
        updated = true;
      }
      if (!updated) {
        return;
      }
      try {
        companyRepository.save(fresh);
        return;
      } catch (ObjectOptimisticLockingFailureException ex) {
        if (attempt == 1) {
          throw ex;
        }
      }
    }
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

  private Dealer ensureDealer(Company company, Account arAccount) {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, "TS-DEALER")
        .orElseGet(
            () -> {
              Dealer dealer = new Dealer();
              dealer.setCompany(company);
              dealer.setCode("TS-DEALER");
              dealer.setName("Truthsuite Dealer");
              dealer.setStatus("ACTIVE");
              dealer.setReceivableAccount(arAccount);
              return dealerRepository.save(dealer);
            });
  }

  private FinishedGood ensureFinishedGoodWithCatalog(
      Company company, Map<String, Account> accounts, String sku, BigDecimal gstRate) {
    FinishedGoodRequest request =
        new FinishedGoodRequest(
            sku,
            sku + " Name",
            "UNIT",
            "FIFO",
            accounts.get("INV").getId(),
            accounts.get("COGS").getId(),
            accounts.get("REV").getId(),
            accounts.get("DISC").getId(),
            accounts.get("GST_OUT").getId());
    FinishedGood finishedGood =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, sku)
            .orElseGet(
                () -> {
                  var dto = finishedGoodsService.createFinishedGood(request);
                  return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
    ensureCatalogProduct(company, finishedGood, gstRate);
    return finishedGood;
  }

  private void ensureCatalogProduct(
      Company company, FinishedGood finishedGood, BigDecimal gstRate) {
    ProductionBrand brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(company, "TS-BRAND")
            .orElseGet(
                () -> {
                  ProductionBrand created = new ProductionBrand();
                  created.setCompany(company);
                  created.setCode("TS-BRAND");
                  created.setName("Truthsuite Brand");
                  return productionBrandRepository.save(created);
                });
    productionProductRepository
        .findByCompanyAndSkuCode(company, finishedGood.getProductCode())
        .orElseGet(
            () -> {
              ProductionProduct product = new ProductionProduct();
              product.setCompany(company);
              product.setBrand(brand);
              product.setSkuCode(finishedGood.getProductCode());
              product.setProductName(finishedGood.getName());
              product.setBasePrice(new BigDecimal("10.00"));
              product.setCategory("GENERAL");
              product.setSizeLabel("STD");
              product.setDefaultColour("NA");
              product.setMinDiscountPercent(BigDecimal.ZERO);
              product.setMinSellingPrice(BigDecimal.ZERO);
              product.setMetadata(new java.util.HashMap<>());
              product.setGstRate(gstRate);
              product.setUnitOfMeasure("UNIT");
              product.setActive(true);
              return productionProductRepository.save(product);
            });
  }

  private SalesOrder createOrder(
      Company company,
      Dealer dealer,
      String productCode,
      BigDecimal quantity,
      BigDecimal unitPrice) {
    CompanyContextHolder.setCompanyCode(company.getCode());
    BigDecimal totalAmount = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    var orderDto =
        salesService.createOrder(
            new SalesOrderRequest(
                dealer.getId(),
                totalAmount,
                "INR",
                "truthsuite replay characterization",
                List.of(
                    new SalesOrderItemRequest(
                        productCode, "Truthsuite Item", quantity, unitPrice, BigDecimal.ZERO)),
                "EXCLUSIVE",
                null,
                null,
                UUID.randomUUID().toString()));
    return salesOrderRepository.findById(orderDto.id()).orElseThrow();
  }

  private long countJournalEntries(Company company, String referenceNumber) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from journal_entries where company_id = ? and reference_number = ?",
            Integer.class,
            company.getId(),
            referenceNumber);
    return count == null ? 0 : count.longValue();
  }

  private long countInvoicesForSlip(String dispatchNote, Company company, SalesOrder order) {
    return invoiceRepository.findAllByCompanyAndSalesOrderId(company, order.getId()).stream()
        .filter(invoice -> dispatchNote.equals(invoice.getNotes()))
        .count();
  }

  private String dispatchNoteFor(PackagingSlip slip) {
    return "Dispatch " + slip.getSlipNumber();
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private record DispatchFixture(
      Company company,
      Dealer dealer,
      FinishedGood finishedGood,
      SalesOrder order,
      PackagingSlip slip,
      DispatchConfirmRequest salesRequest,
      DispatchConfirmationRequest inventoryRequest) {}
}
