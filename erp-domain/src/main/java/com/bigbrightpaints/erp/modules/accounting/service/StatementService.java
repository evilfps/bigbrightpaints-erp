package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerStatementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.StatementTransactionDto;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class StatementService {

    private final CompanyContextService companyContextService;
    private final DealerRepository dealerRepository;
    private final SupplierRepository supplierRepository;
    private final DealerLedgerRepository dealerLedgerRepository;
    private final SupplierLedgerRepository supplierLedgerRepository;
    private final CompanyClock companyClock;

    public StatementService(CompanyContextService companyContextService,
                            DealerRepository dealerRepository,
                            SupplierRepository supplierRepository,
                            DealerLedgerRepository dealerLedgerRepository,
                            SupplierLedgerRepository supplierLedgerRepository,
                            CompanyClock companyClock) {
        this.companyContextService = companyContextService;
        this.dealerRepository = dealerRepository;
        this.supplierRepository = supplierRepository;
        this.dealerLedgerRepository = dealerLedgerRepository;
        this.supplierLedgerRepository = supplierLedgerRepository;
        this.companyClock = companyClock;
    }

    public PartnerStatementResponse dealerStatement(Long dealerId, LocalDate from, LocalDate to) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        LocalDate today = companyClock.today(company);
        LocalDate start = from == null ? today.minusMonths(6) : from;
        LocalDate end = to == null ? today : to;
        validateStatementRange(start, end);

        BigDecimal opening = dealerLedgerRepository.findByCompanyAndDealerAndEntryDateBeforeOrderByEntryDateAsc(company, dealer, start)
                .stream()
                .map(e -> safe(e.getDebit()).subtract(safe(e.getCredit())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<StatementTransactionDto> txns = new ArrayList<>();
        BigDecimal running = opening;
        List<DealerLedgerEntry> periodEntries = dealerLedgerRepository
                .findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAsc(company, dealer, start, end);
        periodEntries.sort(Comparator.comparing(DealerLedgerEntry::getEntryDate).thenComparing(DealerLedgerEntry::getId));
        for (DealerLedgerEntry entry : periodEntries) {
            BigDecimal delta = safe(entry.getDebit()).subtract(safe(entry.getCredit()));
            running = running.add(delta);
            txns.add(new StatementTransactionDto(
                    entry.getEntryDate(),
                    entry.getReferenceNumber(),
                    entry.getMemo(),
                    entry.getDebit(),
                    entry.getCredit(),
                    running,
                    entry.getJournalEntry() != null ? entry.getJournalEntry().getId() : null
            ));
        }
        return new PartnerStatementResponse(
                dealer.getId(),
                dealer.getName(),
                start,
                end,
                opening,
                running,
                txns);
    }

    public PartnerStatementResponse supplierStatement(Long supplierId, LocalDate from, LocalDate to) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierRepository.findByCompanyAndId(company, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
        LocalDate today = companyClock.today(company);
        LocalDate start = from == null ? today.minusMonths(6) : from;
        LocalDate end = to == null ? today : to;
        validateStatementRange(start, end);

        BigDecimal opening = supplierLedgerRepository.findByCompanyAndSupplierAndEntryDateBeforeOrderByEntryDateAsc(company, supplier, start)
                .stream()
                .map(e -> safe(e.getCredit()).subtract(safe(e.getDebit())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<StatementTransactionDto> txns = new ArrayList<>();
        BigDecimal running = opening;
        List<SupplierLedgerEntry> periodEntries = supplierLedgerRepository
                .findByCompanyAndSupplierAndEntryDateBetweenOrderByEntryDateAsc(company, supplier, start, end);
        periodEntries.sort(Comparator.comparing(SupplierLedgerEntry::getEntryDate).thenComparing(SupplierLedgerEntry::getId));
        for (SupplierLedgerEntry entry : periodEntries) {
            BigDecimal delta = safe(entry.getCredit()).subtract(safe(entry.getDebit()));
            running = running.add(delta);
            txns.add(new StatementTransactionDto(
                    entry.getEntryDate(),
                    entry.getReferenceNumber(),
                    entry.getMemo(),
                    entry.getDebit(),
                    entry.getCredit(),
                    running,
                    entry.getJournalEntry() != null ? entry.getJournalEntry().getId() : null
            ));
        }
        return new PartnerStatementResponse(
                supplier.getId(),
                supplier.getName(),
                start,
                end,
                opening,
                running,
                txns);
    }

    public AgingSummaryResponse dealerAging(Long dealerId, LocalDate asOf, String bucketParam) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        LocalDate ref = asOf == null ? companyClock.today(company) : asOf;
        List<int[]> buckets = parseBuckets(bucketParam);
        List<DealerLedgerEntry> entries = new ArrayList<>(
                dealerLedgerRepository.findByCompanyAndDealerOrderByEntryDateAsc(company, dealer)
        );
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal[] bucketTotals = new BigDecimal[buckets.size()];
        for (int i = 0; i < bucketTotals.length; i++) bucketTotals[i] = BigDecimal.ZERO;

        entries.sort(Comparator.comparing(DealerLedgerEntry::getEntryDate).thenComparing(DealerLedgerEntry::getId));
        List<AgingLine> openInvoices = new ArrayList<>();
        BigDecimal creditPool = BigDecimal.ZERO;
        for (DealerLedgerEntry e : entries) {
            if (e.getEntryDate().isAfter(ref)) {
                break;
            }
            BigDecimal delta = safe(e.getDebit()).subtract(safe(e.getCredit()));
            balance = balance.add(delta);
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                openInvoices.add(new AgingLine(resolveAgingDate(e), delta));
            } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                creditPool = creditPool.add(delta.abs());
            }
        }
        if (creditPool.compareTo(BigDecimal.ZERO) > 0) {
            openInvoices.sort(Comparator.comparing(AgingLine::date));
            for (int i = 0; i < openInvoices.size() && creditPool.compareTo(BigDecimal.ZERO) > 0; i++) {
                AgingLine line = openInvoices.get(i);
                BigDecimal applied = creditPool.min(line.amount());
                BigDecimal remaining = line.amount().subtract(applied);
                openInvoices.set(i, new AgingLine(line.date(), remaining));
                creditPool = creditPool.subtract(applied);
            }
        }
        for (AgingLine line : openInvoices) {
            if (line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            long age = java.time.temporal.ChronoUnit.DAYS.between(line.date(), ref);
            if (age < 0) {
                age = 0;
            }
            for (int i = 0; i < buckets.size(); i++) {
                int[] b = buckets.get(i);
                int from = b[0];
                Integer to = b.length > 1 ? b[1] : null;
                boolean inBucket = age >= from && (to == null || age <= to);
                if (inBucket) {
                    bucketTotals[i] = bucketTotals[i].add(line.amount());
                    break;
                }
            }
        }
        if (creditPool.compareTo(BigDecimal.ZERO) > 0 && bucketTotals.length > 0) {
            bucketTotals[0] = bucketTotals[0].subtract(creditPool);
        }
        List<AgingBucketDto> bucketDtos = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            int[] b = buckets.get(i);
            String label = b[0] + (b.length > 1 ? "-" + b[1] : "+") + " days";
            bucketDtos.add(new AgingBucketDto(label, b[0], b.length > 1 ? b[1] : null, bucketTotals[i]));
        }
        return new AgingSummaryResponse(dealer.getId(), dealer.getName(), balance, bucketDtos);
    }

    public AgingSummaryResponse supplierAging(Long supplierId, LocalDate asOf, String bucketParam) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierRepository.findByCompanyAndId(company, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
        LocalDate ref = asOf == null ? companyClock.today(company) : asOf;
        List<int[]> buckets = parseBuckets(bucketParam);
        List<SupplierLedgerEntry> entries = new ArrayList<>(
                supplierLedgerRepository.findByCompanyAndSupplierOrderByEntryDateAsc(company, supplier)
        );
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal[] bucketTotals = new BigDecimal[buckets.size()];
        for (int i = 0; i < bucketTotals.length; i++) bucketTotals[i] = BigDecimal.ZERO;

        entries.sort(Comparator.comparing(SupplierLedgerEntry::getEntryDate).thenComparing(SupplierLedgerEntry::getId));
        List<AgingLine> openInvoices = new ArrayList<>();
        BigDecimal creditPool = BigDecimal.ZERO;
        for (SupplierLedgerEntry e : entries) {
            if (e.getEntryDate().isAfter(ref)) {
                break;
            }
            BigDecimal delta = safe(e.getCredit()).subtract(safe(e.getDebit()));
            balance = balance.add(delta);
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                openInvoices.add(new AgingLine(e.getEntryDate(), delta));
            } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                creditPool = creditPool.add(delta.abs());
            }
        }
        if (creditPool.compareTo(BigDecimal.ZERO) > 0) {
            openInvoices.sort(Comparator.comparing(AgingLine::date));
            for (int i = 0; i < openInvoices.size() && creditPool.compareTo(BigDecimal.ZERO) > 0; i++) {
                AgingLine line = openInvoices.get(i);
                BigDecimal applied = creditPool.min(line.amount());
                BigDecimal remaining = line.amount().subtract(applied);
                openInvoices.set(i, new AgingLine(line.date(), remaining));
                creditPool = creditPool.subtract(applied);
            }
        }
        for (AgingLine line : openInvoices) {
            if (line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            long age = java.time.temporal.ChronoUnit.DAYS.between(line.date(), ref);
            if (age < 0) {
                age = 0;
            }
            for (int i = 0; i < buckets.size(); i++) {
                int[] b = buckets.get(i);
                int from = b[0];
                Integer to = b.length > 1 ? b[1] : null;
                boolean inBucket = age >= from && (to == null || age <= to);
                if (inBucket) {
                    bucketTotals[i] = bucketTotals[i].add(line.amount());
                    break;
                }
            }
        }
        if (creditPool.compareTo(BigDecimal.ZERO) > 0 && bucketTotals.length > 0) {
            bucketTotals[0] = bucketTotals[0].subtract(creditPool);
        }
        List<AgingBucketDto> bucketDtos = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            int[] b = buckets.get(i);
            String label = b[0] + (b.length > 1 ? "-" + b[1] : "+") + " days";
            bucketDtos.add(new AgingBucketDto(label, b[0], b.length > 1 ? b[1] : null, bucketTotals[i]));
        }
        return new AgingSummaryResponse(supplier.getId(), supplier.getName(), balance, bucketDtos);
    }

    public byte[] dealerStatementPdf(Long dealerId, LocalDate from, LocalDate to) {
        PartnerStatementResponse stmt = dealerStatement(dealerId, from, to);
        return buildStatementPdf("Dealer Statement", stmt);
    }

    public byte[] supplierStatementPdf(Long supplierId, LocalDate from, LocalDate to) {
        PartnerStatementResponse stmt = supplierStatement(supplierId, from, to);
        return buildStatementPdf("Supplier Statement", stmt);
    }

    public byte[] dealerAgingPdf(Long dealerId, LocalDate asOf, String buckets) {
        AgingSummaryResponse aging = dealerAging(dealerId, asOf, buckets);
        return buildAgingPdf("Dealer Aging", aging);
    }

    public byte[] supplierAgingPdf(Long supplierId, LocalDate asOf, String buckets) {
        AgingSummaryResponse aging = supplierAging(supplierId, asOf, buckets);
        return buildAgingPdf("Supplier Aging", aging);
    }

    private List<int[]> parseBuckets(String bucketParam) {
        String def = "0-30,31-60,61-90,91";
        String value = StringUtils.hasText(bucketParam) ? bucketParam : def;
        String[] parts = value.split(",");
        List<int[]> buckets = new ArrayList<>();
        Integer previousUpperBound = null;
        for (int i = 0; i < parts.length; i++) {
            String trimmed = parts[i].trim();
            if (trimmed.isEmpty()) {
                throw invalidBucketFormat(bucketParam);
            }
            if (trimmed.contains("-")) {
                String[] range = trimmed.split("-");
                if (range.length != 2) {
                    throw invalidBucketFormat(bucketParam);
                }
                int from = parseBucketBound(range[0], bucketParam);
                int to = parseBucketBound(range[1], bucketParam);
                if (from > to) {
                    throw invalidBucketFormat(bucketParam);
                }
                if (previousUpperBound != null && from <= previousUpperBound) {
                    throw invalidBucketFormat(bucketParam);
                }
                previousUpperBound = to;
                buckets.add(new int[]{from, to});
            } else {
                int from = parseBucketBound(trimmed, bucketParam);
                if (previousUpperBound != null && from <= previousUpperBound) {
                    throw invalidBucketFormat(bucketParam);
                }
                if (i != parts.length - 1) {
                    throw invalidBucketFormat(bucketParam);
                }
                previousUpperBound = Integer.MAX_VALUE;
                buckets.add(new int[]{from});
            }
        }
        if (buckets.isEmpty()) {
            throw invalidBucketFormat(bucketParam);
        }
        return buckets;
    }

    private int parseBucketBound(String raw, String original) {
        String trimmed = raw == null ? "" : raw.trim();
        try {
            int parsed = Integer.parseInt(trimmed);
            if (parsed < 0) {
                throw invalidBucketFormat(original);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalidBucketFormat(original);
        }
    }

    private ApplicationException invalidBucketFormat(String bucketParam) {
        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                "Invalid aging bucket format")
                .withDetail("bucketParam", bucketParam);
    }

    private void validateStatementRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return;
        }
        if (start.isAfter(end)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "from date cannot be after to date")
                    .withDetail("from", start.toString())
                    .withDetail("to", end.toString());
        }
    }

    private LocalDate resolveAgingDate(DealerLedgerEntry entry) {
        if (entry == null) {
            Company company = companyContextService.requireCurrentCompany();
            return CompanyTime.today(company);
        }
        return entry.getDueDate() != null ? entry.getDueDate() : entry.getEntryDate();
    }

    private record AgingLine(LocalDate date, BigDecimal amount) {
    }

    private BigDecimal safe(BigDecimal val) {
        return val == null ? BigDecimal.ZERO : val;
    }

    private byte[] buildStatementPdf(String title, PartnerStatementResponse stmt) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n")
                .append("Partner: ").append(stmt.partnerName()).append("\n")
                .append("Period: ").append(stmt.fromDate()).append(" to ").append(stmt.toDate()).append("\n")
                .append("Opening Balance: ").append(stmt.openingBalance()).append("\n")
                .append("Closing Balance: ").append(stmt.closingBalance()).append("\n\n")
                .append("Date,Reference,Description,Debit,Credit,Balance\n");
        stmt.transactions().forEach(tx -> sb.append(tx.entryDate()).append(",")
                .append(tx.referenceNumber()).append(",")
                .append(tx.memo() != null ? tx.memo().replace(",", " ") : "").append(",")
                .append(tx.debit()).append(",")
                .append(tx.credit()).append(",")
                .append(tx.runningBalance()).append("\n"));
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] buildAgingPdf(String title, AgingSummaryResponse aging) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n")
                .append("Partner: ").append(aging.partnerName()).append("\n")
                .append("Bucket,Amount\n");
        aging.buckets().forEach(b -> sb.append(b.label()).append(",").append(b.amount()).append("\n"));
        sb.append("Total,").append(aging.totalOutstanding()).append("\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
