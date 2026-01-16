package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerStatementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.StatementTransactionDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
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
        List<DealerLedgerEntry> entries = dealerLedgerRepository.findByCompanyAndDealerOrderByEntryDateAsc(company, dealer);
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal[] bucketTotals = new BigDecimal[buckets.size()];
        for (int i = 0; i < bucketTotals.length; i++) bucketTotals[i] = BigDecimal.ZERO;

        entries.sort(Comparator.comparing(DealerLedgerEntry::getEntryDate).thenComparing(DealerLedgerEntry::getId));
        for (DealerLedgerEntry e : entries) {
            if (e.getEntryDate().isAfter(ref)) {
                break;
            }
            BigDecimal delta = safe(e.getDebit()).subtract(safe(e.getCredit()));
            balance = balance.add(delta);
            long age = java.time.temporal.ChronoUnit.DAYS.between(e.getEntryDate(), ref);
            for (int i = 0; i < buckets.size(); i++) {
                int[] b = buckets.get(i);
                int from = b[0];
                Integer to = b.length > 1 ? b[1] : null;
                boolean inBucket = age >= from && (to == null || age <= to);
                if (inBucket) {
                    bucketTotals[i] = bucketTotals[i].add(delta);
                    break;
                }
            }
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
        List<SupplierLedgerEntry> entries = supplierLedgerRepository.findByCompanyAndSupplierOrderByEntryDateAsc(company, supplier);
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal[] bucketTotals = new BigDecimal[buckets.size()];
        for (int i = 0; i < bucketTotals.length; i++) bucketTotals[i] = BigDecimal.ZERO;

        entries.sort(Comparator.comparing(SupplierLedgerEntry::getEntryDate).thenComparing(SupplierLedgerEntry::getId));
        for (SupplierLedgerEntry e : entries) {
            if (e.getEntryDate().isAfter(ref)) {
                break;
            }
            BigDecimal delta = safe(e.getCredit()).subtract(safe(e.getDebit()));
            balance = balance.add(delta);
            long age = java.time.temporal.ChronoUnit.DAYS.between(e.getEntryDate(), ref);
            for (int i = 0; i < buckets.size(); i++) {
                int[] b = buckets.get(i);
                int from = b[0];
                Integer to = b.length > 1 ? b[1] : null;
                boolean inBucket = age >= from && (to == null || age <= to);
                if (inBucket) {
                    bucketTotals[i] = bucketTotals[i].add(delta);
                    break;
                }
            }
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
        for (String part : parts) {
            String trimmed = part.trim();
            try {
                if (trimmed.contains("-")) {
                    String[] range = trimmed.split("-");
                    if (range.length != 2) {
                        throw new NumberFormatException("Invalid range: " + trimmed);
                    }
                    int from = Integer.parseInt(range[0].trim());
                    int to = Integer.parseInt(range[1].trim());
                    if (from < 0 || to < 0 || from > to) {
                        throw new NumberFormatException("Invalid bucket bounds: " + trimmed);
                    }
                    buckets.add(new int[]{from, to});
                } else {
                    int bucketValue = Integer.parseInt(trimmed);
                    if (bucketValue < 0) {
                        throw new NumberFormatException("Negative bucket: " + trimmed);
                    }
                    buckets.add(new int[]{bucketValue});
                }
            } catch (NumberFormatException ex) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Invalid aging buckets format: " + bucketParam, ex);
            }
        }
        return buckets;
    }

    private BigDecimal safe(BigDecimal val) {
        return val == null ? BigDecimal.ZERO : val;
    }

    private byte[] buildStatementPdf(String title, PartnerStatementResponse stmt) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>")
                .append("body{font-family:Arial, sans-serif;font-size:10pt;}")
                .append("table{width:100%;border-collapse:collapse;}")
                .append("th,td{border:1px solid #999;padding:4px;text-align:left;}")
                .append("th{background:#f0f0f0;}")
                .append("</style></head><body>");
        html.append("<h2>").append(escapeHtml(title)).append("</h2>");
        html.append("<p><strong>Partner:</strong> ").append(escapeHtml(stmt.partnerName())).append("<br/>");
        html.append("<strong>Period:</strong> ").append(stmt.fromDate()).append(" to ").append(stmt.toDate()).append("<br/>");
        html.append("<strong>Opening Balance:</strong> ").append(stmt.openingBalance()).append("<br/>");
        html.append("<strong>Closing Balance:</strong> ").append(stmt.closingBalance()).append("</p>");
        html.append("<table><thead><tr>")
                .append("<th>Date</th><th>Reference</th><th>Description</th><th>Debit</th><th>Credit</th><th>Balance</th>")
                .append("</tr></thead><tbody>");
        stmt.transactions().forEach(tx -> html.append("<tr>")
                .append("<td>").append(tx.entryDate()).append("</td>")
                .append("<td>").append(escapeHtml(tx.referenceNumber())).append("</td>")
                .append("<td>").append(escapeHtml(tx.memo())).append("</td>")
                .append("<td>").append(tx.debit()).append("</td>")
                .append("<td>").append(tx.credit()).append("</td>")
                .append("<td>").append(tx.runningBalance()).append("</td>")
                .append("</tr>"));
        html.append("</tbody></table></body></html>");
        return renderPdf(html.toString());
    }

    private byte[] buildAgingPdf(String title, AgingSummaryResponse aging) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>")
                .append("body{font-family:Arial, sans-serif;font-size:10pt;}")
                .append("table{width:100%;border-collapse:collapse;}")
                .append("th,td{border:1px solid #999;padding:4px;text-align:left;}")
                .append("th{background:#f0f0f0;}")
                .append("</style></head><body>");
        html.append("<h2>").append(escapeHtml(title)).append("</h2>");
        html.append("<p><strong>Partner:</strong> ").append(escapeHtml(aging.partnerName())).append("</p>");
        html.append("<table><thead><tr><th>Bucket</th><th>Amount</th></tr></thead><tbody>");
        aging.buckets().forEach(b -> html.append("<tr>")
                .append("<td>").append(escapeHtml(b.label())).append("</td>")
                .append("<td>").append(b.amount()).append("</td>")
                .append("</tr>"));
        html.append("<tr><th>Total</th><th>").append(aging.totalOutstanding()).append("</th></tr>");
        html.append("</tbody></table></body></html>");
        return renderPdf(html.toString());
    }

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, "");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new ApplicationException(ErrorCode.SYSTEM_INTERNAL_ERROR, "Failed to generate PDF", ex);
        }
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
