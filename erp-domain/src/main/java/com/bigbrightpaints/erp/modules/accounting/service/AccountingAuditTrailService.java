package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEvent;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AccountingAuditTrailService {

    private static final JournalTotals ZERO_TOTALS = new JournalTotals(BigDecimal.ZERO, BigDecimal.ZERO);

    private final CompanyContextService companyContextService;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final AccountingEventRepository accountingEventRepository;
    private final PartnerSettlementAllocationRepository settlementAllocationRepository;
    private final InvoiceRepository invoiceRepository;
    private final EntityManager entityManager;

    public AccountingAuditTrailService(CompanyContextService companyContextService,
                                       JournalEntryRepository journalEntryRepository,
                                       JournalLineRepository journalLineRepository,
                                       AccountingEventRepository accountingEventRepository,
                                       PartnerSettlementAllocationRepository settlementAllocationRepository,
                                       InvoiceRepository invoiceRepository,
                                       EntityManager entityManager) {
        this.companyContextService = companyContextService;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.accountingEventRepository = accountingEventRepository;
        this.settlementAllocationRepository = settlementAllocationRepository;
        this.invoiceRepository = invoiceRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingTransactionAuditListItemDto> listTransactions(LocalDate from,
                                                                                LocalDate to,
                                                                                String module,
                                                                                String status,
                                                                                String referenceNumber,
                                                                                int page,
                                                                                int size) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate end = to != null ? to : CompanyTime.today();
        LocalDate start = from != null ? from : end.minusDays(30);
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 200));

        Specification<JournalEntry> spec = Specification.where(byCompany(company))
                .and(byEntryDateRange(start, end))
                .and(byStatus(status))
                .and(byReference(referenceNumber))
                .and(byModule(module));

        Page<JournalEntry> data = journalEntryRepository.findAll(
                spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "entryDate", "id")));

        List<Long> journalIds = data.getContent().stream().map(JournalEntry::getId).toList();
        if (journalIds.isEmpty()) {
            return PageResponse.of(List.of(), data.getTotalElements(), safePage, safeSize);
        }
        Map<Long, JournalTotals> totalsByJournal = journalLineRepository
                .summarizeTotalsByCompanyAndJournalEntryIds(company, journalIds)
                .stream()
                .collect(Collectors.toMap(
                        JournalLineRepository.JournalEntryLineTotals::getJournalEntryId,
                        row -> new JournalTotals(
                                row.getTotalDebit() != null ? row.getTotalDebit() : BigDecimal.ZERO,
                                row.getTotalCredit() != null ? row.getTotalCredit() : BigDecimal.ZERO)));
        Map<Long, Invoice> invoiceByJournal = invoiceRepository.findByCompanyAndJournalEntry_IdIn(company, journalIds).stream()
                .filter(invoice -> invoice.getJournalEntry() != null && invoice.getJournalEntry().getId() != null)
                .collect(Collectors.toMap(invoice -> invoice.getJournalEntry().getId(), invoice -> invoice, (left, right) -> left));
        Map<Long, RawMaterialPurchase> purchaseByJournal = findPurchasesByJournalEntryIds(company, journalIds);
        Map<Long, List<PartnerSettlementAllocation>> allocationsByJournal = settlementAllocationRepository
                .findByCompanyAndJournalEntry_IdIn(company, journalIds).stream()
                .collect(Collectors.groupingBy(allocation -> allocation.getJournalEntry().getId()));

        List<AccountingTransactionAuditListItemDto> rows = data.getContent().stream().map(entry -> {
            JournalTotals totals = totalsByJournal.getOrDefault(entry.getId(), ZERO_TOTALS);
            BigDecimal totalDebit = totals.totalDebit();
            BigDecimal totalCredit = totals.totalCredit();
            Invoice invoice = invoiceByJournal.get(entry.getId());
            RawMaterialPurchase purchase = purchaseByJournal.get(entry.getId());
            List<PartnerSettlementAllocation> allocations = allocationsByJournal.getOrDefault(entry.getId(), List.of());
            String transactionType = deriveTransactionType(entry, invoice, purchase, allocations);
            String resolvedModule = deriveModule(transactionType, entry.getReferenceNumber());
            String consistency = assessConsistency(entry, allocations, totalDebit, totalCredit).status();
            return new AccountingTransactionAuditListItemDto(
                    entry.getId(),
                    entry.getReferenceNumber(),
                    entry.getEntryDate(),
                    entry.getStatus(),
                    resolvedModule,
                    transactionType,
                    entry.getMemo(),
                    entry.getDealer() != null ? entry.getDealer().getId() : null,
                    entry.getDealer() != null ? entry.getDealer().getName() : null,
                    entry.getSupplier() != null ? entry.getSupplier().getId() : null,
                    entry.getSupplier() != null ? entry.getSupplier().getName() : null,
                    totalDebit,
                    totalCredit,
                    entry.getReversalOf() != null ? entry.getReversalOf().getId() : null,
                    entry.getReversalEntry() != null ? entry.getReversalEntry().getId() : null,
                    entry.getCorrectionType() != null ? entry.getCorrectionType().name() : null,
                    consistency,
                    entry.getPostedAt()
            );
        }).toList();

        return PageResponse.of(rows, data.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public AccountingTransactionAuditDetailDto transactionDetail(Long journalEntryId) {
        Company company = companyContextService.requireCurrentCompany();
        JournalEntry entry = journalEntryRepository.findByCompanyAndId(company, journalEntryId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Journal entry not found"));

        List<PartnerSettlementAllocation> allocations = settlementAllocationRepository
                .findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry);
        Optional<Invoice> invoice = invoiceRepository.findByCompanyAndJournalEntry(company, entry);
        Optional<RawMaterialPurchase> purchase = findPurchaseByJournalEntry(company, entry);
        List<AccountingEvent> events = accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(entry.getId());

        BigDecimal totalDebit = entry.getLines().stream()
                .map(JournalLine::getDebit)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = entry.getLines().stream()
                .map(JournalLine::getCredit)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ConsistencyResult consistency = assessConsistency(entry, allocations, totalDebit, totalCredit);
        String transactionType = deriveTransactionType(entry, invoice.orElse(null), purchase.orElse(null), allocations);
        String module = deriveModule(transactionType, entry.getReferenceNumber());

        List<JournalLineDto> lines = entry.getLines().stream()
                .sorted(Comparator.comparing(line -> line.getAccount().getCode()))
                .map(line -> new JournalLineDto(
                        line.getAccount().getId(),
                        line.getAccount().getCode(),
                        line.getDescription(),
                        line.getDebit(),
                        line.getCredit()))
                .toList();

        List<AccountingTransactionAuditDetailDto.LinkedDocument> linkedDocuments = new ArrayList<>();
        invoice.ifPresent(value -> linkedDocuments.add(new AccountingTransactionAuditDetailDto.LinkedDocument(
                "INVOICE",
                value.getId(),
                value.getInvoiceNumber(),
                value.getStatus(),
                value.getTotalAmount(),
                value.getOutstandingAmount()
        )));
        purchase.ifPresent(value -> linkedDocuments.add(new AccountingTransactionAuditDetailDto.LinkedDocument(
                "PURCHASE",
                value.getId(),
                value.getInvoiceNumber(),
                value.getStatus(),
                value.getTotalAmount(),
                value.getOutstandingAmount()
        )));
        for (PartnerSettlementAllocation allocation : allocations) {
            if (allocation.getInvoice() != null) {
                Invoice allocInvoice = allocation.getInvoice();
                linkedDocuments.add(new AccountingTransactionAuditDetailDto.LinkedDocument(
                        "SETTLEMENT_INVOICE",
                        allocInvoice.getId(),
                        allocInvoice.getInvoiceNumber(),
                        allocInvoice.getStatus(),
                        allocInvoice.getTotalAmount(),
                        allocInvoice.getOutstandingAmount()
                ));
            }
            if (allocation.getPurchase() != null) {
                RawMaterialPurchase allocPurchase = allocation.getPurchase();
                linkedDocuments.add(new AccountingTransactionAuditDetailDto.LinkedDocument(
                        "SETTLEMENT_PURCHASE",
                        allocPurchase.getId(),
                        allocPurchase.getInvoiceNumber(),
                        allocPurchase.getStatus(),
                        allocPurchase.getTotalAmount(),
                        allocPurchase.getOutstandingAmount()
                ));
            }
        }
        List<AccountingTransactionAuditDetailDto.LinkedDocument> dedupedLinkedDocuments = linkedDocuments.stream()
                .collect(Collectors.toMap(
                        item -> item.documentType() + ":" + item.documentId(),
                        item -> item,
                        (left, right) -> left))
                .values()
                .stream()
                .toList();

        List<AccountingTransactionAuditDetailDto.SettlementAllocation> allocationRows = allocations.stream()
                .map(allocation -> new AccountingTransactionAuditDetailDto.SettlementAllocation(
                        allocation.getId(),
                        allocation.getPartnerType() != null ? allocation.getPartnerType().name() : null,
                        allocation.getDealer() != null ? allocation.getDealer().getId() : null,
                        allocation.getSupplier() != null ? allocation.getSupplier().getId() : null,
                        allocation.getInvoice() != null ? allocation.getInvoice().getId() : null,
                        allocation.getPurchase() != null ? allocation.getPurchase().getId() : null,
                        allocation.getAllocationAmount(),
                        allocation.getDiscountAmount(),
                        allocation.getWriteOffAmount(),
                        allocation.getFxDifferenceAmount(),
                        allocation.getMemo(),
                        allocation.getSettlementDate(),
                        allocation.getIdempotencyKey()
                ))
                .toList();

        List<AccountingTransactionAuditDetailDto.EventTrailItem> eventTrail = events.stream()
                .map(event -> new AccountingTransactionAuditDetailDto.EventTrailItem(
                        event.getId(),
                        event.getEventType() != null ? event.getEventType().name() : null,
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getSequenceNumber(),
                        event.getEventTimestamp(),
                        event.getEffectiveDate(),
                        event.getAccountId(),
                        event.getAccountCode(),
                        event.getDebitAmount(),
                        event.getCreditAmount(),
                        event.getBalanceBefore(),
                        event.getBalanceAfter(),
                        event.getDescription(),
                        event.getUserId(),
                        event.getCorrelationId()
                ))
                .toList();

        return new AccountingTransactionAuditDetailDto(
                entry.getId(),
                entry.getPublicId(),
                entry.getReferenceNumber(),
                entry.getEntryDate(),
                entry.getStatus(),
                module,
                transactionType,
                entry.getMemo(),
                entry.getDealer() != null ? entry.getDealer().getId() : null,
                entry.getDealer() != null ? entry.getDealer().getName() : null,
                entry.getSupplier() != null ? entry.getSupplier().getId() : null,
                entry.getSupplier() != null ? entry.getSupplier().getName() : null,
                entry.getAccountingPeriod() != null ? entry.getAccountingPeriod().getId() : null,
                entry.getAccountingPeriod() != null ? entry.getAccountingPeriod().getLabel() : null,
                entry.getAccountingPeriod() != null && entry.getAccountingPeriod().getStatus() != null
                        ? entry.getAccountingPeriod().getStatus().name()
                        : null,
                entry.getReversalOf() != null ? entry.getReversalOf().getId() : null,
                entry.getReversalEntry() != null ? entry.getReversalEntry().getId() : null,
                entry.getCorrectionType() != null ? entry.getCorrectionType().name() : null,
                entry.getCorrectionReason(),
                entry.getVoidReason(),
                totalDebit,
                totalCredit,
                consistency.status(),
                consistency.notes(),
                lines,
                dedupedLinkedDocuments,
                allocationRows,
                eventTrail,
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getPostedAt(),
                entry.getCreatedBy(),
                entry.getPostedBy(),
                entry.getLastModifiedBy()
        );
    }

    private Map<Long, RawMaterialPurchase> findPurchasesByJournalEntryIds(Company company, List<Long> journalEntryIds) {
        List<RawMaterialPurchase> purchases = entityManager.createQuery("""
                select p
                from RawMaterialPurchase p
                where p.company = :company
                  and p.journalEntry.id in :journalEntryIds
                """, RawMaterialPurchase.class)
                .setParameter("company", company)
                .setParameter("journalEntryIds", journalEntryIds)
                .getResultList();
        return purchases.stream()
                .filter(purchase -> purchase.getJournalEntry() != null && purchase.getJournalEntry().getId() != null)
                .collect(Collectors.toMap(
                        purchase -> purchase.getJournalEntry().getId(),
                        purchase -> purchase,
                        (left, right) -> left));
    }

    private Optional<RawMaterialPurchase> findPurchaseByJournalEntry(Company company, JournalEntry journalEntry) {
        List<RawMaterialPurchase> purchases = entityManager.createQuery("""
                select p
                from RawMaterialPurchase p
                where p.company = :company
                  and p.journalEntry = :journalEntry
                """, RawMaterialPurchase.class)
                .setParameter("company", company)
                .setParameter("journalEntry", journalEntry)
                .setMaxResults(1)
                .getResultList();
        return purchases.stream().findFirst();
    }

    private Specification<JournalEntry> byCompany(Company company) {
        return (root, query, cb) -> cb.equal(root.get("company"), company);
    }

    private Specification<JournalEntry> byEntryDateRange(LocalDate from, LocalDate to) {
        return (root, query, cb) -> cb.between(root.get("entryDate"), from, to);
    }

    private Specification<JournalEntry> byStatus(String status) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(status)) {
                return cb.conjunction();
            }
            return cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT));
        };
    }

    private Specification<JournalEntry> byReference(String reference) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(reference)) {
                return cb.conjunction();
            }
            return cb.like(cb.upper(root.get("referenceNumber")), "%" + reference.trim().toUpperCase(Locale.ROOT) + "%");
        };
    }

    private Specification<JournalEntry> byModule(String module) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(module)) {
                return cb.conjunction();
            }
            String normalized = module.trim().toUpperCase(Locale.ROOT);
            List<String> prefixes = moduleReferencePrefixes(normalized);
            if (prefixes.isEmpty()) {
                return cb.conjunction();
            }
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            for (String prefix : prefixes) {
                predicates.add(cb.like(cb.upper(root.get("referenceNumber")), prefix + "%"));
            }
            return cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private List<String> moduleReferencePrefixes(String module) {
        return switch (module) {
            case "SALES" -> List.of("INV", "CRN", "SR");
            case "PURCHASING" -> List.of("RMP", "DBN", "PUR", "GRN");
            case "SETTLEMENT" -> List.of("SET", "RCPT", "SUP-SET", "SUPPLIER-SETTLEMENT", "DEALER-SETTLEMENT");
            case "PAYROLL" -> List.of("PAY", "PRL", "SAL");
            case "INVENTORY" -> List.of("ADJ", "REVAL", "WIP", "PACK", "BULK");
            case "REVERSAL", "ADJUSTMENT" -> List.of("REV", "VOID");
            default -> List.of();
        };
    }

    private String deriveTransactionType(JournalEntry entry,
                                         Invoice invoice,
                                         RawMaterialPurchase purchase,
                                         List<PartnerSettlementAllocation> allocations) {
        if (entry.getReversalOf() != null) {
            return "REVERSAL_ENTRY";
        }
        if (entry.getReversalEntry() != null) {
            return "REVERSED_ORIGINAL";
        }
        if (invoice != null) {
            return "SALES_INVOICE";
        }
        if (purchase != null) {
            return "PURCHASE_INVOICE";
        }
        if (allocations != null && !allocations.isEmpty()) {
            Set<String> partnerTypes = allocations.stream()
                    .map(allocation -> allocation.getPartnerType() != null ? allocation.getPartnerType().name() : "UNKNOWN")
                    .collect(Collectors.toSet());
            if (partnerTypes.size() == 1) {
                return "SETTLEMENT_" + partnerTypes.iterator().next();
            }
            return "SETTLEMENT_MIXED";
        }
        String reference = entry.getReferenceNumber() != null ? entry.getReferenceNumber().toUpperCase(Locale.ROOT) : "";
        if (reference.startsWith("PAY") || reference.contains("PAYROLL")) {
            return "PAYROLL_ENTRY";
        }
        if (reference.startsWith("ADJ") || reference.startsWith("REVAL") || reference.startsWith("WIP")) {
            return "INVENTORY_ADJUSTMENT";
        }
        if (entry.getDealer() != null && entry.getSupplier() == null) {
            return "DEALER_JOURNAL";
        }
        if (entry.getSupplier() != null && entry.getDealer() == null) {
            return "SUPPLIER_JOURNAL";
        }
        return "GENERAL_JOURNAL";
    }

    private String deriveModule(String transactionType, String referenceNumber) {
        String normalizedType = transactionType != null ? transactionType.toUpperCase(Locale.ROOT) : "";
        if (normalizedType.contains("SALES") || normalizedType.contains("DEALER")) {
            return "SALES";
        }
        if (normalizedType.contains("PURCHASE") || normalizedType.contains("SUPPLIER")) {
            return "PURCHASING";
        }
        if (normalizedType.contains("SETTLEMENT")) {
            return "SETTLEMENT";
        }
        if (normalizedType.contains("PAYROLL")) {
            return "PAYROLL";
        }
        if (normalizedType.contains("INVENTORY")) {
            return "INVENTORY";
        }
        if (normalizedType.contains("REVERSAL")) {
            return "ADJUSTMENT";
        }
        String reference = referenceNumber != null ? referenceNumber.toUpperCase(Locale.ROOT) : "";
        if (reference.startsWith("INV") || reference.startsWith("CRN")) {
            return "SALES";
        }
        if (reference.startsWith("RMP") || reference.startsWith("DBN")) {
            return "PURCHASING";
        }
        if (reference.startsWith("SET") || reference.startsWith("RCPT") || reference.contains("SETTLEMENT")) {
            return "SETTLEMENT";
        }
        return "ACCOUNTING";
    }

    private ConsistencyResult assessConsistency(JournalEntry entry,
                                                List<PartnerSettlementAllocation> allocations,
                                                BigDecimal totalDebit,
                                                BigDecimal totalCredit) {
        List<String> notes = new ArrayList<>();
        String status = "OK";
        if (totalDebit.compareTo(totalCredit) != 0) {
            notes.add("Journal is not balanced: total debit and credit differ.");
            status = "ERROR";
        }
        if ("POSTED".equalsIgnoreCase(entry.getStatus()) && entry.getPostedAt() == null) {
            notes.add("Entry is POSTED but postedAt is null.");
            if (!"ERROR".equals(status)) {
                status = "WARNING";
            }
        }
        if ("REVERSED".equalsIgnoreCase(entry.getStatus()) && entry.getReversalEntry() == null) {
            notes.add("Entry status is REVERSED but reversal link is missing.");
            status = "ERROR";
        }
        if ("VOIDED".equalsIgnoreCase(entry.getStatus()) && entry.getReversalEntry() == null) {
            notes.add("Entry status is VOIDED but void reversal link is missing.");
            status = "ERROR";
        }
        String ref = entry.getReferenceNumber() != null ? entry.getReferenceNumber().toUpperCase(Locale.ROOT) : "";
        boolean likelySettlement = ref.contains("SETTLEMENT") || ref.startsWith("SET") || ref.startsWith("RCPT");
        if (likelySettlement && (allocations == null || allocations.isEmpty())) {
            notes.add("Settlement-like reference has no settlement allocation rows.");
            if (!"ERROR".equals(status)) {
                status = "WARNING";
            }
        }
        return new ConsistencyResult(status, notes);
    }

    private record ConsistencyResult(String status, List<String> notes) {
    }

    private record JournalTotals(BigDecimal totalDebit, BigDecimal totalCredit) {
    }
}
