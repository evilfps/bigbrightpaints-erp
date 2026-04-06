package com.bigbrightpaints.erp.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;

public class ErpInvariantAssertions {

  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  private final CompanyRepository companyRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final InvoiceRepository invoiceRepository;
  private final RawMaterialPurchaseRepository purchaseRepository;
  private final PayrollRunRepository payrollRunRepository;
  private final PackagingSlipRepository packagingSlipRepository;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final DealerLedgerRepository dealerLedgerRepository;
  private final SupplierLedgerRepository supplierLedgerRepository;
  private final AccountRepository accountRepository;
  private final TemporalBalanceService temporalBalanceService;

  public ErpInvariantAssertions(
      CompanyRepository companyRepository,
      JournalEntryRepository journalEntryRepository,
      InvoiceRepository invoiceRepository,
      RawMaterialPurchaseRepository purchaseRepository,
      PayrollRunRepository payrollRunRepository,
      PackagingSlipRepository packagingSlipRepository,
      InventoryMovementRepository inventoryMovementRepository,
      FinishedGoodRepository finishedGoodRepository,
      RawMaterialRepository rawMaterialRepository,
      DealerLedgerRepository dealerLedgerRepository,
      SupplierLedgerRepository supplierLedgerRepository,
      AccountRepository accountRepository,
      TemporalBalanceService temporalBalanceService) {
    this.companyRepository = companyRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.invoiceRepository = invoiceRepository;
    this.purchaseRepository = purchaseRepository;
    this.payrollRunRepository = payrollRunRepository;
    this.packagingSlipRepository = packagingSlipRepository;
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.dealerLedgerRepository = dealerLedgerRepository;
    this.supplierLedgerRepository = supplierLedgerRepository;
    this.accountRepository = accountRepository;
    this.temporalBalanceService = temporalBalanceService;
  }

  public void assertJournalBalanced(Long entryId) {
    JournalEntry entry =
        journalEntryRepository
            .findById(entryId)
            .orElseThrow(() -> new AssertionError("Journal entry missing: " + entryId));
    BigDecimal debits =
        entry.getLines().stream()
            .map(JournalLine::getDebit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credits =
        entry.getLines().stream()
            .map(JournalLine::getCredit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(debits.subtract(credits).abs())
        .as("journal %s must balance", entry.getReferenceNumber())
        .isLessThanOrEqualTo(TOLERANCE);
  }

  public void assertJournalLinkedTo(String sourceType, Long sourceId) {
    String normalized = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
    switch (normalized) {
      case "INVOICE" -> {
        Invoice invoice =
            invoiceRepository
                .findById(sourceId)
                .orElseThrow(() -> new AssertionError("Invoice missing: " + sourceId));
        JournalEntry journalEntry = invoice.getJournalEntry();
        assertThat(journalEntry)
            .as("invoice %s should link to journal entry", invoice.getInvoiceNumber())
            .isNotNull();
        assertSameCompanyIds(
            "invoice journal",
            requireCompanyId(invoice.getCompany(), "invoice"),
            requireJournalCompanyId(journalEntry.getId(), "invoice journal"));
      }
      case "RAW_MATERIAL_PURCHASE", "PURCHASE" -> {
        RawMaterialPurchase purchase =
            purchaseRepository
                .findById(sourceId)
                .orElseThrow(() -> new AssertionError("Purchase missing: " + sourceId));
        JournalEntry journalEntry = purchase.getJournalEntry();
        assertThat(journalEntry)
            .as("purchase %s should link to journal entry", purchase.getInvoiceNumber())
            .isNotNull();
        assertSameCompanyIds(
            "purchase journal",
            requireCompanyId(purchase.getCompany(), "purchase"),
            requireJournalCompanyId(journalEntry.getId(), "purchase journal"));
      }
      case "PAYROLL_RUN" -> {
        PayrollRun run =
            payrollRunRepository
                .findById(sourceId)
                .orElseThrow(() -> new AssertionError("Payroll run missing: " + sourceId));
        boolean linked = run.getJournalEntry() != null || run.getJournalEntryId() != null;
        assertThat(linked).as("payroll run %s should link to journal entry", run.getId()).isTrue();
        Long journalEntryId = run.getJournalEntryId();
        if (journalEntryId == null && run.getJournalEntry() != null) {
          journalEntryId = run.getJournalEntry().getId();
        }
        if (journalEntryId != null) {
          assertSameCompanyIds(
              "payroll journal",
              requireCompanyId(run.getCompany(), "payroll run"),
              requireJournalCompanyId(journalEntryId, "payroll journal"));
        }
      }
      case "PACKAGING_SLIP" -> {
        PackagingSlip slip =
            packagingSlipRepository
                .findById(sourceId)
                .orElseThrow(() -> new AssertionError("Packaging slip missing: " + sourceId));
        boolean linked = slip.getJournalEntryId() != null || slip.getCogsJournalEntryId() != null;
        assertThat(linked)
            .as("packaging slip %s should link to journal entry", slip.getSlipNumber())
            .isTrue();
        Long expectedCompanyId = requireCompanyId(slip.getCompany(), "packaging slip");
        if (slip.getJournalEntryId() != null) {
          assertSameCompanyIds(
              "packaging slip journal",
              expectedCompanyId,
              requireJournalCompanyId(slip.getJournalEntryId(), "packaging slip journal"));
        }
        if (slip.getCogsJournalEntryId() != null) {
          assertSameCompanyIds(
              "packaging slip COGS journal",
              expectedCompanyId,
              requireJournalCompanyId(slip.getCogsJournalEntryId(), "packaging slip COGS journal"));
        }
      }
      case "INVENTORY_MOVEMENT" -> {
        InventoryMovement movement =
            inventoryMovementRepository
                .findById(sourceId)
                .orElseThrow(() -> new AssertionError("Inventory movement missing: " + sourceId));
        assertThat(movement.getJournalEntryId())
            .as("inventory movement %s should link to journal entry", movement.getId())
            .isNotNull();
        FinishedGood finishedGood = movement.getFinishedGood();
        assertThat(finishedGood)
            .as("inventory movement %s should link to finished good", movement.getId())
            .isNotNull();
        assertSameCompanyIds(
            "inventory journal",
            requireCompanyId(finishedGood.getCompany(), "inventory movement"),
            requireJournalCompanyId(movement.getJournalEntryId(), "inventory journal"));
      }
      default ->
          throw new AssertionError("Unsupported sourceType for journal linkage: " + sourceType);
    }
  }

  public void assertReversalCreatesBalancedInverse(Long originalEntryId) {
    JournalEntry original =
        journalEntryRepository
            .findById(originalEntryId)
            .orElseThrow(
                () -> new AssertionError("Original journal entry missing: " + originalEntryId));
    Optional<JournalEntry> reversal =
        journalEntryRepository.findByCompanyAndReversalOf(original.getCompany(), original).stream()
            .findFirst();
    JournalEntry reversalEntry =
        reversal.orElseThrow(
            () -> new AssertionError("Reversal entry missing for journal " + originalEntryId));

    assertSameCompanyIds(
        "reversal journal",
        requireJournalCompanyId(original.getId(), "original journal"),
        requireJournalCompanyId(reversalEntry.getId(), "reversal journal"));
    assertJournalBalanced(reversalEntry.getId());

    Map<Long, BigDecimal> originalNet = aggregateNetByAccount(original);
    Map<Long, BigDecimal> reversalNet = aggregateNetByAccount(reversalEntry);

    for (Map.Entry<Long, BigDecimal> entry : originalNet.entrySet()) {
      BigDecimal reversed = reversalNet.get(entry.getKey());
      assertThat(reversed).as("reversal should include account %s", entry.getKey()).isNotNull();
      assertThat(reversed)
          .as("reversal should invert account %s", entry.getKey())
          .isEqualByComparingTo(entry.getValue().negate());
    }
  }

  public void assertNoNegativeStock(Long companyId, String skuOrProductCode) {
    Company company =
        companyRepository
            .findById(companyId)
            .orElseThrow(() -> new AssertionError("Company missing: " + companyId));
    Optional<FinishedGood> finishedGood =
        finishedGoodRepository.findByCompanyAndProductCode(company, skuOrProductCode);
    if (finishedGood.isPresent()) {
      FinishedGood fg = finishedGood.get();
      assertThat(fg.getCurrentStock())
          .as("finished good stock non-negative")
          .isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(fg.getReservedStock())
          .as("finished good reserved non-negative")
          .isGreaterThanOrEqualTo(BigDecimal.ZERO);
      return;
    }
    Optional<RawMaterial> rawMaterial =
        rawMaterialRepository.findByCompanyAndSku(company, skuOrProductCode);
    if (rawMaterial.isPresent()) {
      assertThat(rawMaterial.get().getCurrentStock())
          .as("raw material stock non-negative")
          .isGreaterThanOrEqualTo(BigDecimal.ZERO);
      return;
    }
    throw new AssertionError("No stock item found for code " + skuOrProductCode);
  }

  public void assertSubledgerReconciles(Long controlAccountId, LocalDate asOfDate) {
    Account account =
        accountRepository
            .findById(controlAccountId)
            .orElseThrow(() -> new AssertionError("Account missing: " + controlAccountId));
    Long companyId = account.getCompany() != null ? account.getCompany().getId() : null;
    if (companyId == null) {
      throw new AssertionError(
          "Account is missing company for subledger reconciliation: " + account.getCode());
    }
    Company company =
        companyRepository
            .findById(companyId)
            .orElseThrow(
                () -> new AssertionError("Company missing for account " + account.getCode()));
    LocalDate targetDate = asOfDate != null ? asOfDate : LocalDate.now();

    BigDecimal controlBalance =
        withCompanyContext(
            company.getCode(),
            () -> temporalBalanceService.getBalanceAsOfDate(controlAccountId, targetDate));

    BigDecimal ledgerBalance;
    if (isReceivable(account)) {
      ledgerBalance =
          dealerLedgerRepository.findAll().stream()
              .filter(entry -> matchesCompany(entry.getCompany(), company))
              .filter(entry -> isOnOrBefore(entry.getEntryDate(), targetDate))
              .map(entry -> entry.getDebit().subtract(entry.getCredit()))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    } else if (isPayable(account)) {
      ledgerBalance =
          supplierLedgerRepository.findAll().stream()
              .filter(entry -> matchesCompany(entry.getCompany(), company))
              .filter(entry -> isOnOrBefore(entry.getEntryDate(), targetDate))
              .map(entry -> entry.getCredit().subtract(entry.getDebit()))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    } else {
      throw new AssertionError("Account is not AR/AP control: " + account.getCode());
    }

    BigDecimal variance = controlBalance.subtract(ledgerBalance);
    assertThat(variance.abs())
        .as("subledger should reconcile to control account %s", account.getCode())
        .isLessThanOrEqualTo(TOLERANCE);
  }

  private Map<Long, BigDecimal> aggregateNetByAccount(JournalEntry entry) {
    Map<Long, BigDecimal> net = new HashMap<>();
    for (JournalLine line : entry.getLines()) {
      Long accountId = line.getAccount().getId();
      BigDecimal amount = line.getDebit().subtract(line.getCredit());
      net.merge(accountId, amount, BigDecimal::add);
    }
    return net;
  }

  private boolean isReceivable(Account account) {
    String code = account.getCode() != null ? account.getCode().toUpperCase(Locale.ROOT) : "";
    return account.getType().isDebitNormalBalance()
        && (code.contains("AR") || code.contains("RECEIVABLE"));
  }

  private boolean isPayable(Account account) {
    String code = account.getCode() != null ? account.getCode().toUpperCase(Locale.ROOT) : "";
    return !account.getType().isDebitNormalBalance()
        && (code.contains("AP") || code.contains("PAYABLE"));
  }

  private boolean matchesCompany(Company entryCompany, Company company) {
    return entryCompany != null && company != null && entryCompany.getId().equals(company.getId());
  }

  private Long requireCompanyId(Company company, String label) {
    assertThat(company).as("%s missing expected company", label).isNotNull();
    assertThat(company.getId()).as("%s missing company id", label).isNotNull();
    return company.getId();
  }

  private Long requireJournalCompanyId(Long journalEntryId, String label) {
    return journalEntryRepository
        .findCompanyIdById(journalEntryId)
        .orElseThrow(
            () -> new AssertionError(label + " missing company id for journal " + journalEntryId));
  }

  private void assertSameCompanyIds(String label, Long expectedCompanyId, Long actualCompanyId) {
    assertThat(expectedCompanyId).as("%s missing expected company id", label).isNotNull();
    assertThat(actualCompanyId).as("%s missing linked company id", label).isNotNull();
    assertThat(actualCompanyId).as("%s company should match", label).isEqualTo(expectedCompanyId);
  }

  private boolean isOnOrBefore(LocalDate entryDate, LocalDate targetDate) {
    return entryDate == null || !entryDate.isAfter(targetDate);
  }

  private <T> T withCompanyContext(String companyCode, java.util.concurrent.Callable<T> work) {
    String previous = CompanyContextHolder.getCompanyCode();
    try {
      CompanyContextHolder.setCompanyCode(companyCode);
      return work.call();
    } catch (Exception ex) {
      throw new AssertionError("Failed to resolve balances for company " + companyCode, ex);
    } finally {
      if (previous == null) {
        CompanyContextHolder.clear();
      } else {
        CompanyContextHolder.setCompanyCode(previous);
      }
    }
  }
}
