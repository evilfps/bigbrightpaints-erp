package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DealerPortalService {

    private final DealerRepository dealerRepository;
    private final DealerLedgerService dealerLedgerService;
    private final InvoiceRepository invoiceRepository;
    private final InvoicePdfService invoicePdfService;
    private final DealerService dealerService;
    private final SalesOrderRepository salesOrderRepository;

    public DealerPortalService(DealerRepository dealerRepository,
                               DealerLedgerService dealerLedgerService,
                               InvoiceRepository invoiceRepository,
                               InvoicePdfService invoicePdfService,
                               DealerService dealerService,
                               SalesOrderRepository salesOrderRepository) {
        this.dealerRepository = dealerRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.invoiceRepository = invoiceRepository;
        this.invoicePdfService = invoicePdfService;
        this.dealerService = dealerService;
        this.salesOrderRepository = salesOrderRepository;
    }

    public Dealer getCurrentDealer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("No authenticated user");
        }
        String email = auth.getName();
        return dealerRepository.findByPortalUserEmail(email)
                .orElseThrow(() -> new IllegalStateException("No dealer account linked to user: " + email));
    }

    public boolean isDealerUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DEALER"));
    }

    public void verifyDealerAccess(Long dealerId) {
        if (!isDealerUser()) return;
        Dealer currentDealer = getCurrentDealer();
        if (!currentDealer.getId().equals(dealerId)) {
            throw new SecurityException("Access denied: You can only view your own data");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMyLedger() {
        Dealer dealer = getCurrentDealer();
        return dealerService.ledgerView(dealer.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLedgerForDealer(Long dealerId) {
        verifyDealerAccess(dealerId);
        return dealerService.ledgerView(dealerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMyInvoices() {
        Dealer dealer = getCurrentDealer();
        return buildInvoicesView(dealer);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getInvoicesForDealer(Long dealerId) {
        verifyDealerAccess(dealerId);
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        return buildInvoicesView(dealer);
    }

    private Map<String, Object> buildInvoicesView(Dealer dealer) {
        List<Invoice> invoices = invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(
                dealer.getCompany(), dealer);
        
        List<Map<String, Object>> invoiceList = new ArrayList<>();
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        
        for (Invoice inv : invoices) {
            Map<String, Object> invMap = new LinkedHashMap<>();
            invMap.put("id", inv.getId());
            invMap.put("invoiceNumber", inv.getInvoiceNumber());
            invMap.put("issueDate", inv.getIssueDate());
            invMap.put("dueDate", inv.getDueDate());
            invMap.put("totalAmount", inv.getTotalAmount());
            invMap.put("outstandingAmount", inv.getOutstandingAmount());
            invMap.put("status", inv.getStatus());
            invMap.put("currency", inv.getCurrency());
            invoiceList.add(invMap);
            
            if (inv.getOutstandingAmount() != null) {
                totalOutstanding = totalOutstanding.add(inv.getOutstandingAmount());
            }
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dealerId", dealer.getId());
        result.put("dealerName", dealer.getName());
        result.put("totalOutstanding", totalOutstanding);
        result.put("invoiceCount", invoices.size());
        result.put("invoices", invoiceList);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMyOutstandingAndAging() {
        Dealer dealer = getCurrentDealer();
        return buildAgingView(dealer);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAgingForDealer(Long dealerId) {
        verifyDealerAccess(dealerId);
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        return buildAgingView(dealer);
    }

    private Map<String, Object> buildAgingView(Dealer dealer) {
        List<Invoice> invoices = invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(
                dealer.getCompany(), dealer);
        
        LocalDate today = LocalDate.now();
        
        BigDecimal current = BigDecimal.ZERO;      // Not yet due
        BigDecimal days1to30 = BigDecimal.ZERO;    // 1-30 days overdue
        BigDecimal days31to60 = BigDecimal.ZERO;   // 31-60 days overdue
        BigDecimal days61to90 = BigDecimal.ZERO;   // 61-90 days overdue
        BigDecimal over90 = BigDecimal.ZERO;       // 90+ days overdue
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        
        List<Map<String, Object>> overdueInvoices = new ArrayList<>();
        
        for (Invoice inv : invoices) {
            BigDecimal outstanding = inv.getOutstandingAmount();
            if (outstanding == null || outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            
            totalOutstanding = totalOutstanding.add(outstanding);
            LocalDate dueDate = inv.getDueDate();
            
            if (dueDate == null || !today.isAfter(dueDate)) {
                current = current.add(outstanding);
            } else {
                long daysOverdue = ChronoUnit.DAYS.between(dueDate, today);
                
                if (daysOverdue <= 30) {
                    days1to30 = days1to30.add(outstanding);
                } else if (daysOverdue <= 60) {
                    days31to60 = days31to60.add(outstanding);
                } else if (daysOverdue <= 90) {
                    days61to90 = days61to90.add(outstanding);
                } else {
                    over90 = over90.add(outstanding);
                }
                
                Map<String, Object> overdueInv = new LinkedHashMap<>();
                overdueInv.put("invoiceNumber", inv.getInvoiceNumber());
                overdueInv.put("issueDate", inv.getIssueDate());
                overdueInv.put("dueDate", dueDate);
                overdueInv.put("daysOverdue", daysOverdue);
                overdueInv.put("outstandingAmount", outstanding);
                overdueInvoices.add(overdueInv);
            }
        }
        
        Map<String, Object> agingBuckets = new LinkedHashMap<>();
        agingBuckets.put("current", current);
        agingBuckets.put("1-30 days", days1to30);
        agingBuckets.put("31-60 days", days31to60);
        agingBuckets.put("61-90 days", days61to90);
        agingBuckets.put("90+ days", over90);
        
        BigDecimal creditLimit = dealer.getCreditLimit() != null ? dealer.getCreditLimit() : BigDecimal.ZERO;
        BigDecimal availableCredit = creditLimit.subtract(totalOutstanding);
        if (availableCredit.compareTo(BigDecimal.ZERO) < 0) {
            availableCredit = BigDecimal.ZERO;
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dealerId", dealer.getId());
        result.put("dealerName", dealer.getName());
        result.put("creditLimit", creditLimit);
        result.put("totalOutstanding", totalOutstanding);
        result.put("availableCredit", availableCredit);
        result.put("agingBuckets", agingBuckets);
        result.put("overdueInvoices", overdueInvoices);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMyDashboard() {
        Dealer dealer = getCurrentDealer();
        
        BigDecimal currentBalance = dealerLedgerService.currentBalance(dealer.getId());
        Map<String, Object> aging = buildAgingView(dealer);
        
        List<Invoice> invoices = invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(
                dealer.getCompany(), dealer);
        long pendingInvoices = invoices.stream()
                .filter(i -> i.getOutstandingAmount() != null && i.getOutstandingAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dealerId", dealer.getId());
        result.put("dealerName", dealer.getName());
        result.put("dealerCode", dealer.getCode());
        result.put("currentBalance", currentBalance);
        result.put("creditLimit", dealer.getCreditLimit());
        result.put("availableCredit", aging.get("availableCredit"));
        result.put("totalOutstanding", aging.get("totalOutstanding"));
        result.put("pendingInvoices", pendingInvoices);
        result.put("agingBuckets", aging.get("agingBuckets"));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMyOrders() {
        Dealer dealer = getCurrentDealer();
        List<SalesOrder> orders = salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(
                dealer.getCompany(), dealer);
        
        List<Map<String, Object>> orderList = new ArrayList<>();
        for (SalesOrder order : orders) {
            Map<String, Object> orderMap = new LinkedHashMap<>();
            orderMap.put("id", order.getId());
            orderMap.put("orderNumber", order.getOrderNumber());
            orderMap.put("status", order.getStatus());
            orderMap.put("totalAmount", order.getTotalAmount());
            orderMap.put("createdAt", order.getCreatedAt());
            orderMap.put("notes", order.getNotes());
            orderList.add(orderMap);
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dealerId", dealer.getId());
        result.put("dealerName", dealer.getName());
        result.put("orderCount", orders.size());
        result.put("orders", orderList);
        return result;
    }

    @Transactional(readOnly = true)
    public InvoicePdfService.PdfDocument getMyInvoicePdf(Long invoiceId) {
        Dealer dealer = getCurrentDealer();
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));

        if (invoice.getDealer() == null
                || invoice.getDealer().getId() == null
                || invoice.getCompany() == null
                || dealer.getId() == null
                || dealer.getCompany() == null
                || dealer.getCompany().getId() == null
                || invoice.getCompany().getId() == null
                || !dealer.getCompany().getId().equals(invoice.getCompany().getId())
                || !dealer.getId().equals(invoice.getDealer().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found");
        }

        return invoicePdfService.renderInvoicePdf(invoiceId);
    }
}
