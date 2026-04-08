package com.bigbrightpaints.erp.modules.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

class AccountingDomainDtoCoverageTest {

  @Test
  void closedPeriodPostingException_prePersistPopulatesPublicIdAndApprovedAt() {
    Company company = company();
    ClosedPeriodPostingException exception = new ClosedPeriodPostingException();
    exception.setCompany(company);
    exception.setAccountingPeriod(period(company));
    exception.setDocumentType("JOURNAL");
    exception.setDocumentReference("JE-100");
    exception.setReason("reason");
    exception.setApprovedBy("admin");

    exception.prePersist();

    JournalEntry journalEntry = new JournalEntry();
    exception.setJournalEntry(journalEntry);
    exception.setUsedBy("admin");
    exception.setUsedAt(exception.getApprovedAt());

    assertThat(exception.getPublicId()).isNotNull();
    assertThat(exception.getApprovedAt()).isNotNull();
    assertThat(exception.getCompany()).isSameAs(company);
    assertThat(exception.getAccountingPeriod()).isNotNull();
    assertThat(exception.getDocumentType()).isEqualTo("JOURNAL");
    assertThat(exception.getDocumentReference()).isEqualTo("JE-100");
    assertThat(exception.getReason()).isEqualTo("reason");
    assertThat(exception.getApprovedBy()).isEqualTo("admin");
    assertThat(exception.getUsedBy()).isEqualTo("admin");
    assertThat(exception.getUsedAt()).isEqualTo(exception.getApprovedAt());
    assertThat(exception.getJournalEntry()).isSameAs(journalEntry);
  }

  @Test
  void closedPeriodPostingException_prePersistPreservesExistingIdentityAndApprovalInstant() {
    Company company = company();
    ClosedPeriodPostingException exception = new ClosedPeriodPostingException();
    exception.setCompany(company);
    exception.setAccountingPeriod(period(company));
    exception.setDocumentType("JOURNAL");
    exception.setDocumentReference("JE-101");
    exception.setReason("reason");
    exception.setApprovedBy("admin");
    UUID existingPublicId = UUID.randomUUID();
    Instant approvedAt = Instant.parse("2026-03-12T09:15:00Z");
    ReflectionFieldAccess.setField(exception, "publicId", existingPublicId);
    exception.setApprovedAt(approvedAt);

    exception.prePersist();

    assertThat(exception.getPublicId()).isEqualTo(existingPublicId);
    assertThat(exception.getApprovedAt()).isEqualTo(approvedAt);
  }

  @Test
  void journalEntry_lifecycleAndForeignCurrencySettersBehaveCanonically() {
    Company company = company();
    JournalEntry entry = new JournalEntry();
    entry.setCompany(company);
    entry.setReferenceNumber("JE-100");
    entry.setEntryDate(LocalDate.of(2026, 3, 12));
    entry.setMemo("memo");
    entry.setStatus("POSTED");
    entry.setSourceModule("ACCOUNTING");
    entry.setSourceReference("SRC-1");
    entry.setAttachmentReferences("att-1");
    entry.setCorrectionType(JournalCorrectionType.REVERSAL);
    entry.setCorrectionReason("reclassify");
    entry.setVoidReason("void");
    entry.setVoidedAt(Instant.parse("2026-03-12T10:15:30Z"));
    entry.setCreatedBy("maker");
    entry.setPostedBy("checker");
    entry.setLastModifiedBy("checker");
    entry.setCurrency("USD");
    entry.setForeignAmountTotal(new BigDecimal("125.50"));
    entry.setJournalType(null);

    entry.prePersist();
    Instant createdAt = entry.getCreatedAt();
    entry.preUpdate();

    assertThat(entry.getPublicId()).isNotNull();
    assertThat(entry.getCreatedAt()).isNotNull();
    assertThat(entry.getUpdatedAt()).isNotNull();
    assertThat(entry.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    assertThat(entry.getPostedAt()).isNotNull();
    assertThat(entry.getJournalType()).isEqualTo(JournalEntryType.AUTOMATED);
    assertThat(entry.getReferenceNumber()).isEqualTo("JE-100");
    assertThat(entry.getEntryDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(entry.getMemo()).isEqualTo("memo");
    assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
    assertThat(entry.getSourceModule()).isEqualTo("ACCOUNTING");
    assertThat(entry.getSourceReference()).isEqualTo("SRC-1");
    assertThat(entry.getAttachmentReferences()).isEqualTo("att-1");
    assertThat(entry.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(entry.getCorrectionReason()).isEqualTo("reclassify");
    assertThat(entry.getVoidReason()).isEqualTo("void");
    assertThat(entry.getVoidedAt()).isEqualTo(Instant.parse("2026-03-12T10:15:30Z"));
    assertThat(entry.getCreatedBy()).isEqualTo("maker");
    assertThat(entry.getPostedBy()).isEqualTo("checker");
    assertThat(entry.getLastModifiedBy()).isEqualTo("checker");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getForeignAmountTotal()).isEqualTo(new BigDecimal("125.50"));

    entry.setFxRate(new BigDecimal("1.250000"));
    assertThat(entry.getFxRate()).isEqualByComparingTo("1.250000");
    assertThatThrownBy(() -> entry.setFxRate(BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FX rate must be positive");
  }

  @Test
  void journalCreationRequest_resolvesExplicitAndGeneratedLines() {
    JournalCreationRequest explicit =
        new JournalCreationRequest(
            new BigDecimal("100.00"),
            10L,
            20L,
            "narration",
            "ACCOUNTING",
            "SRC-1",
            new JournalCreationRequest.GstBreakdown(
                new BigDecimal("84.75"),
                new BigDecimal("7.63"),
                new BigDecimal("7.62"),
                BigDecimal.ZERO),
            List.of(
                new JournalCreationRequest.LineRequest(
                    10L, new BigDecimal("100.00"), BigDecimal.ZERO, "debit")),
            LocalDate.of(2026, 3, 12),
            30L,
            40L,
            true,
            List.of("a.pdf"));

    assertThat(explicit.resolvedLines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.accountId()).isEqualTo(10L);
              assertThat(line.description()).isEqualTo("debit");
              assertThat(line.debit()).isEqualByComparingTo("100.00");
              assertThat(line.credit()).isEqualByComparingTo("0");
            });

    JournalCreationRequest generated =
        new JournalCreationRequest(
            new BigDecimal("55.00"),
            11L,
            22L,
            "auto line",
            "ACCOUNTING",
            "SRC-2",
            null,
            null,
            LocalDate.of(2026, 3, 12),
            null,
            null,
            false);

    assertThat(generated.attachmentReferences()).isEmpty();
    assertThat(generated.resolvedLines()).hasSize(2);
    assertThat(generated.resolvedLines().get(0).accountId()).isEqualTo(11L);
    assertThat(generated.resolvedLines().get(1).accountId()).isEqualTo(22L);
  }

  @Test
  void requestRecordsPreserveCanonicalConstructorDefaults() {
    LocalDate settlementDate = LocalDate.of(2026, 3, 12);
    List<SettlementAllocationRequest> allocations =
        List.of(
            new SettlementAllocationRequest(
                101L,
                null,
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SettlementAllocationApplication.DOCUMENT,
                null));

    PartnerSettlementRequest dealerRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            1L,
            10L,
            11L,
            12L,
            13L,
            14L,
            settlementDate,
            "DR-100",
            "memo",
            "idem-1",
            true,
            allocations);
    PartnerSettlementRequest supplierRequest =
        new PartnerSettlementRequest(
            PartnerType.SUPPLIER,
            2L,
            20L,
            21L,
            22L,
            23L,
            24L,
            settlementDate,
            "SR-200",
            "memo",
            "idem-2",
            false,
            allocations);
    JournalEntryRequest journalEntryRequest =
        new JournalEntryRequest(
            "JE-1",
            settlementDate,
            "memo",
            30L,
            31L,
            true,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    10L, "line", new BigDecimal("10.00"), BigDecimal.ZERO)),
            "USD",
            new BigDecimal("1.10"),
            "ACCOUNTING",
            "SRC-3",
            "MANUAL");
    ManualJournalRequest manualJournalRequest =
        new ManualJournalRequest(
            settlementDate,
            "manual memo",
            "idem-3",
            true,
            List.of(
                new ManualJournalRequest.LineRequest(
                    99L, new BigDecimal("10.00"), "manual", ManualJournalRequest.EntryType.DEBIT)));
    SalesReturnPreviewDto salesReturnPreview =
        new SalesReturnPreviewDto(
            77L,
            "SI-100",
            new BigDecimal("118.00"),
            new BigDecimal("90.00"),
            List.of(
                new SalesReturnPreviewDto.LinePreview(
                    88L,
                    "PIGMENT-RED",
                    new BigDecimal("2.00"),
                    new BigDecimal("0.50"),
                    new BigDecimal("1.50"),
                    new BigDecimal("100.00"),
                    new BigDecimal("18.00"),
                    new BigDecimal("45.00"),
                    new BigDecimal("90.00"))));
    PurchaseReturnPreviewDto purchaseReturnPreview =
        new PurchaseReturnPreviewDto(
            5L,
            "PI-100",
            6L,
            "Titanium Dioxide",
            new BigDecimal("2.00"),
            new BigDecimal("7.00"),
            new BigDecimal("80.00"),
            new BigDecimal("14.40"),
            new BigDecimal("94.40"),
            settlementDate,
            "PR-100");

    assertThat(dealerRequest.amount()).isNull();
    assertThat(dealerRequest.unappliedAmountApplication()).isNull();
    assertThat(dealerRequest.referenceNumber()).isEqualTo("DR-100");
    assertThat(supplierRequest.amount()).isNull();
    assertThat(supplierRequest.unappliedAmountApplication()).isNull();
    assertThat(supplierRequest.referenceNumber()).isEqualTo("SR-200");
    assertThat(journalEntryRequest.attachmentReferences()).isEmpty();
    assertThat(journalEntryRequest.lines()).hasSize(1);
    assertThat(journalEntryRequest.lines().getFirst().foreignCurrencyAmount()).isNull();
    assertThat(manualJournalRequest.attachmentReferences()).isEmpty();
    assertThat(manualJournalRequest.lines().getFirst().entryType())
        .isEqualTo(ManualJournalRequest.EntryType.DEBIT);
    assertThat(salesReturnPreview.invoiceNumber()).isEqualTo("SI-100");
    assertThat(salesReturnPreview.totalReturnAmount()).isEqualByComparingTo("118.00");
    assertThat(salesReturnPreview.totalInventoryValue()).isEqualByComparingTo("90.00");
    assertThat(salesReturnPreview.lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.invoiceLineId()).isEqualTo(88L);
              assertThat(line.productCode()).isEqualTo("PIGMENT-RED");
              assertThat(line.requestedQuantity()).isEqualByComparingTo("2.00");
              assertThat(line.alreadyReturnedQuantity()).isEqualByComparingTo("0.50");
              assertThat(line.remainingQuantityAfterReturn()).isEqualByComparingTo("1.50");
              assertThat(line.lineAmount()).isEqualByComparingTo("100.00");
              assertThat(line.taxAmount()).isEqualByComparingTo("18.00");
              assertThat(line.inventoryUnitCost()).isEqualByComparingTo("45.00");
              assertThat(line.inventoryValue()).isEqualByComparingTo("90.00");
            });
    assertThat(purchaseReturnPreview.purchaseInvoiceNumber()).isEqualTo("PI-100");
    assertThat(purchaseReturnPreview.totalAmount()).isEqualByComparingTo("94.40");
  }

  @Test
  void settlementAllocationApplication_marksOnlyUnappliedModes() {
    assertThat(SettlementAllocationApplication.DOCUMENT.isUnapplied()).isFalse();
    assertThat(SettlementAllocationApplication.ON_ACCOUNT.isUnapplied()).isTrue();
    assertThat(SettlementAllocationApplication.FUTURE_APPLICATION.isUnapplied()).isTrue();
  }

  private Company company() {
    Company company = new Company();
    company.setName("Coverage Co");
    company.setCode("COV-DOM");
    company.setTimezone("UTC");
    return company;
  }

  private AccountingPeriod period(Company company) {
    AccountingPeriod period = new AccountingPeriod();
    period.setCompany(company);
    period.setYear(2026);
    period.setMonth(3);
    period.setStartDate(LocalDate.of(2026, 3, 1));
    period.setEndDate(LocalDate.of(2026, 3, 31));
    return period;
  }
}
