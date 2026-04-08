package com.bigbrightpaints.erp.modules.sales.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventSource;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventStatus;
import com.bigbrightpaints.erp.core.audittrail.AuditCorrelationIdResolver;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderStatusHistory;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderStatusHistoryRepository;

@Service
public class SalesOrderAutoCloseService {

  private static final Set<String> AUTO_CLOSE_ELIGIBLE_ORDER_STATUSES =
      Set.of("INVOICED", "DISPATCHED", "SETTLED", "ON_HOLD");
  private static final Set<String> NON_SETTLEMENT_INVOICE_STATUSES =
      Set.of("DRAFT", "VOID", "REVERSED", "WRITTEN_OFF");

  private final SalesOrderRepository salesOrderRepository;
  private final SalesOrderStatusHistoryRepository salesOrderStatusHistoryRepository;
  private final InvoiceRepository invoiceRepository;
  private final CompanyClock companyClock;
  private final EnterpriseAuditTrailService enterpriseAuditTrailService;

  public SalesOrderAutoCloseService(
      SalesOrderRepository salesOrderRepository,
      SalesOrderStatusHistoryRepository salesOrderStatusHistoryRepository,
      InvoiceRepository invoiceRepository,
      CompanyClock companyClock,
      EnterpriseAuditTrailService enterpriseAuditTrailService) {
    this.salesOrderRepository = salesOrderRepository;
    this.salesOrderStatusHistoryRepository = salesOrderStatusHistoryRepository;
    this.invoiceRepository = invoiceRepository;
    this.companyClock = companyClock;
    this.enterpriseAuditTrailService = enterpriseAuditTrailService;
  }

  @Transactional
  public void autoCloseFullyPaidOrders(Company company, List<Invoice> touchedInvoices) {
    if (company == null || touchedInvoices == null || touchedInvoices.isEmpty()) {
      return;
    }
    Set<Long> touchedOrderIds = collectTouchedSalesOrderIds(touchedInvoices);
    if (touchedOrderIds.isEmpty()) {
      return;
    }
    for (Long orderId : touchedOrderIds) {
      SalesOrder order =
          salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, orderId).orElse(null);
      if (order == null || !isAutoCloseEligibleOrderStatus(order.getStatus())) {
        continue;
      }
      if (!allActionableInvoicesPaid(company, orderId)) {
        continue;
      }
      closeOrder(order);
    }
  }

  private Set<Long> collectTouchedSalesOrderIds(List<Invoice> touchedInvoices) {
    Set<Long> orderIds = new LinkedHashSet<>();
    for (Invoice touchedInvoice : touchedInvoices) {
      if (touchedInvoice == null
          || touchedInvoice.getSalesOrder() == null
          || touchedInvoice.getSalesOrder().getId() == null) {
        continue;
      }
      orderIds.add(touchedInvoice.getSalesOrder().getId());
    }
    return orderIds;
  }

  private boolean allActionableInvoicesPaid(Company company, Long orderId) {
    List<Invoice> linkedInvoices =
        invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId);
    if (linkedInvoices == null || linkedInvoices.isEmpty()) {
      return false;
    }
    boolean hasActionableInvoice = false;
    for (Invoice linkedInvoice : linkedInvoices) {
      if (linkedInvoice == null || isNonSettlementInvoice(linkedInvoice.getStatus())) {
        continue;
      }
      hasActionableInvoice = true;
      if (!isPaid(linkedInvoice)) {
        return false;
      }
    }
    return hasActionableInvoice;
  }

  private boolean isPaid(Invoice invoice) {
    if (invoice == null) {
      return false;
    }
    BigDecimal outstanding =
        invoice.getOutstandingAmount() != null ? invoice.getOutstandingAmount() : BigDecimal.ZERO;
    return outstanding.compareTo(BigDecimal.ZERO) <= 0
        || "PAID".equalsIgnoreCase(normalize(invoice.getStatus()));
  }

  private boolean isNonSettlementInvoice(String status) {
    return NON_SETTLEMENT_INVOICE_STATUSES.contains(normalize(status));
  }

  private boolean isAutoCloseEligibleOrderStatus(String status) {
    return AUTO_CLOSE_ELIGIBLE_ORDER_STATUSES.contains(normalize(status));
  }

  private void closeOrder(SalesOrder order) {
    if (order == null || order.getCompany() == null || order.getId() == null) {
      return;
    }
    String fromStatus = normalize(order.getStatus());
    order.setStatus("CLOSED");
    salesOrderRepository.save(order);

    SalesOrderStatusHistory history = new SalesOrderStatusHistory();
    history.setCompany(order.getCompany());
    history.setSalesOrder(order);
    history.setFromStatus(fromStatus);
    history.setToStatus("CLOSED");
    history.setReasonCode("ORDER_CLOSED_AUTO");
    history.setReason("All linked invoices fully paid");
    history.setChangedBy(resolveActor());
    history.setChangedAt(companyClock.now(order.getCompany()));
    salesOrderStatusHistoryRepository.save(history);
    recordOrderStatusBusinessEvent(order, history);
  }

  private void recordOrderStatusBusinessEvent(SalesOrder order, SalesOrderStatusHistory history) {
    if (enterpriseAuditTrailService == null
        || order == null
        || order.getCompany() == null
        || order.getId() == null
        || history == null) {
      return;
    }
    String action =
        StringUtils.hasText(history.getReasonCode())
            ? history.getReasonCode()
            : "ORDER_STATUS_CHANGED";
    Map<String, String> metadata = new LinkedHashMap<>();
    if (StringUtils.hasText(history.getFromStatus())) {
      metadata.put("fromStatus", history.getFromStatus());
    }
    if (StringUtils.hasText(history.getToStatus())) {
      metadata.put("toStatus", history.getToStatus());
    }
    if (StringUtils.hasText(history.getReason())) {
      metadata.put("reason", history.getReason());
    }
    if (order.getDealer() != null && order.getDealer().getId() != null) {
      metadata.put("dealerId", order.getDealer().getId().toString());
    }
    UUID correlationId =
        AuditCorrelationIdResolver.resolveCorrelationId(
            AuditCorrelationIdResolver.currentRequest(),
            order.getTraceId(),
            order.getOrderNumber(),
            order.getId() != null ? "SALES_ORDER:" + order.getId() : null);
    AuditActionEventCommand command =
        new AuditActionEventCommand(
            order.getCompany(),
            AuditActionEventSource.BACKEND,
            "SALES",
            action,
            "SALES_ORDER",
            order.getId().toString(),
            order.getOrderNumber(),
            AuditActionEventStatus.SUCCESS,
            null,
            order.getTotalAmount(),
            order.getCurrency(),
            correlationId,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            metadata,
            history.getChangedAt());
    dispatchSalesBusinessEvent(command);
  }

  private void dispatchSalesBusinessEvent(AuditActionEventCommand command) {
    if (enterpriseAuditTrailService == null || command == null) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              enterpriseAuditTrailService.recordBusinessEvent(command);
            }
          });
      return;
    }
    enterpriseAuditTrailService.recordBusinessEvent(command);
  }

  private String resolveActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !StringUtils.hasText(authentication.getName())) {
      return "system";
    }
    return authentication.getName().trim();
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
