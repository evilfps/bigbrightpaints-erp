package com.bigbrightpaints.erp.modules.invoice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.BusinessDocumentTruths;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceLineDto;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderCrudService;
import com.bigbrightpaints.erp.shared.dto.DocumentLifecycleDto;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;

import jakarta.transaction.Transactional;

@Service
public class InvoiceService {

  private final CompanyContextService companyContextService;
  private final InvoiceRepository invoiceRepository;
  private final SalesOrderCrudService salesOrderCrudService;
  private final SalesOrderRepository salesOrderRepository;
  private final CompanyScopedSalesLookupService salesLookupService;
  private final CompanyScopedInvoiceLookupService invoiceLookupService;
  private final PackagingSlipRepository packagingSlipRepository;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;

  @Autowired
  public InvoiceService(
      CompanyContextService companyContextService,
      InvoiceRepository invoiceRepository,
      SalesOrderCrudService salesOrderCrudService,
      SalesOrderRepository salesOrderRepository,
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedInvoiceLookupService invoiceLookupService,
      PackagingSlipRepository packagingSlipRepository,
      PartnerSettlementAllocationRepository settlementAllocationRepository) {
    this.companyContextService = companyContextService;
    this.invoiceRepository = invoiceRepository;
    this.salesOrderCrudService = salesOrderCrudService;
    this.salesOrderRepository = salesOrderRepository;
    this.salesLookupService = salesLookupService;
    this.invoiceLookupService = invoiceLookupService;
    this.packagingSlipRepository = packagingSlipRepository;
    this.settlementAllocationRepository = settlementAllocationRepository;
  }

  @Transactional
  public InvoiceDto issueInvoiceForOrder(Long salesOrderId) {
    Company company = companyContextService.requireCurrentCompany();
    List<Invoice> existingInvoices =
        invoiceRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId);
    if (!existingInvoices.isEmpty()) {
      if (existingInvoices.size() == 1) {
        Invoice existing = existingInvoices.get(0);
        SalesOrder existingOrder = requireOrderForUpdate(company, salesOrderId);
        List<PackagingSlip> orderSlips = findOrderSlips(company, salesOrderId, true);
        boolean singleActiveSlipForOrder = hasSingleActiveSlip(orderSlips);
        if (reconcileOrderInvoiceMarker(
            existingOrder, existing.getId(), singleActiveSlipForOrder)) {
          salesOrderRepository.save(existingOrder);
        }
        linkInvoiceToPackagingSlip(existingOrder, existing, orderSlips);
        return toDto(existing);
      }
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Multiple invoices exist for order; issue invoices per dispatch");
    }
    SalesOrder order = requireOrderForUpdate(company, salesOrderId);
    if (order.getDealer() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Dealer is required to issue an invoice");
    }
    List<PackagingSlip> slips = findOrderSlips(company, salesOrderId, true);
    if (!slips.isEmpty()) {
      long activeSlipCount = activeSlipCount(slips);
      if (activeSlipCount > 1) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_REFERENCE,
            "Multiple packing slips exist for order; issue invoices per dispatch");
      }
      PackagingSlip slip = findSingleActiveSlip(slips);
      if (slip == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Dispatch confirmation is required before issuing an invoice; issue invoices per"
                + " dispatch");
      }
      if (!"DISPATCHED".equalsIgnoreCase(slip.getStatus()) || slip.getInvoiceId() == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Final invoice is created by dispatch confirmation; confirm dispatch on the packaging"
                + " slip first");
      }
      return getInvoice(slip.getInvoiceId());
    }
    throw new ApplicationException(
        ErrorCode.VALIDATION_INVALID_INPUT,
        "Dispatch confirmation is required before issuing an invoice; issue invoices per dispatch");
  }

  private void linkInvoiceToPackagingSlip(
      SalesOrder order, Invoice invoice, List<PackagingSlip> orderSlips) {
    if (order == null || invoice == null || invoice.getId() == null) {
      return;
    }
    Company company = order.getCompany();
    if (company == null || order.getId() == null) {
      return;
    }
    List<PackagingSlip> slips =
        orderSlips != null ? orderSlips : findOrderSlips(company, order.getId(), true);
    PackagingSlip slip = findSingleActiveSlip(slips);
    if (slip != null && !invoice.getId().equals(slip.getInvoiceId())) {
      slip.setInvoiceId(invoice.getId());
      packagingSlipRepository.save(slip);
    }
  }

  private SalesOrder requireOrderForUpdate(Company company, Long salesOrderId) {
    Optional<SalesOrder> lockedOrder =
        salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, salesOrderId);
    if (lockedOrder != null && lockedOrder.isPresent()) {
      return lockedOrder.get();
    }
    return salesOrderCrudService.getOrderWithItems(salesOrderId);
  }

  private List<PackagingSlip> findOrderSlips(
      Company company, Long salesOrderId, boolean forUpdate) {
    if (company == null || salesOrderId == null) {
      return List.of();
    }
    List<PackagingSlip> slips = null;
    if (forUpdate) {
      slips =
          packagingSlipRepository.findAllByCompanyAndSalesOrderIdForUpdate(company, salesOrderId);
    }
    if (slips == null || slips.isEmpty()) {
      slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId);
    }
    return slips != null ? slips : List.of();
  }

  private long activeSlipCount(List<PackagingSlip> slips) {
    if (slips == null || slips.isEmpty()) {
      return 0;
    }
    return slips.stream().filter(slip -> !("CANCELLED".equalsIgnoreCase(slip.getStatus()))).count();
  }

  private boolean hasSingleActiveSlip(List<PackagingSlip> slips) {
    return activeSlipCount(slips) == 1;
  }

  private PackagingSlip findSingleActiveSlip(List<PackagingSlip> slips) {
    if (!hasSingleActiveSlip(slips)) {
      return null;
    }
    return slips.stream()
        .filter(slip -> !("CANCELLED".equalsIgnoreCase(slip.getStatus())))
        .findFirst()
        .orElse(null);
  }

  private boolean reconcileOrderInvoiceMarker(
      SalesOrder order, Long invoiceId, boolean singleActiveSlipForOrder) {
    if (order == null) {
      return false;
    }
    if (!singleActiveSlipForOrder) {
      if (order.getFulfillmentInvoiceId() != null) {
        order.setFulfillmentInvoiceId(null);
        return true;
      }
      return false;
    }
    if (!java.util.Objects.equals(order.getFulfillmentInvoiceId(), invoiceId)) {
      order.setFulfillmentInvoiceId(invoiceId);
      return true;
    }
    return false;
  }

  @Transactional
  public List<InvoiceDto> listInvoices(int page, int size) {
    return listInvoices(page, size, null);
  }

  @Transactional
  public List<InvoiceDto> listInvoices(int page, int size, Long salesOrderId) {
    Company company = companyContextService.requireCurrentCompany();
    int safeSize = Math.max(1, Math.min(size, 200));
    PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
    Page<Long> invoiceIds;
    if (salesOrderId != null) {
      invoiceIds =
          invoiceRepository.findIdsByCompanyAndSalesOrderIdOrderByIssueDateDescIdDesc(
              company, salesOrderId, pageable);
    } else {
      invoiceIds = invoiceRepository.findIdsByCompanyOrderByIssueDateDescIdDesc(company, pageable);
    }
    List<Long> ids = invoiceIds.getContent();
    if (ids.isEmpty()) {
      return List.of();
    }
    List<Invoice> invoices =
        invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, ids);
    return toDtos(company, invoices);
  }

  @Transactional
  public List<InvoiceDto> listDealerInvoices(Long dealerId, int page, int size) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer = salesLookupService.requireDealer(company, dealerId);
    int safeSize = Math.max(1, Math.min(size, 200));
    PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
    Page<Long> invoiceIds =
        invoiceRepository.findIdsByCompanyAndDealerOrderByIssueDateDescIdDesc(
            company, dealer, pageable);
    List<Long> ids = invoiceIds.getContent();
    if (ids.isEmpty()) {
      return List.of();
    }
    List<Invoice> invoices =
        invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, ids);
    return toDtos(company, invoices);
  }

  @Transactional
  public List<InvoiceDto> listInvoices() {
    Company company = companyContextService.requireCurrentCompany();
    return toDtos(company, invoiceRepository.findByCompanyOrderByIssueDateDesc(company));
  }

  @Transactional
  public List<InvoiceDto> listDealerInvoices(Long dealerId) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer = salesLookupService.requireDealer(company, dealerId);
    return toDtos(
        company, invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer));
  }

  @Transactional
  public InvoiceDto getInvoice(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    Invoice invoice =
        invoiceRepository
            .findByCompanyAndId(company, id)
            .orElseGet(() -> invoiceLookupService.requireInvoice(company, id));
    return toDto(invoice, buildLinkedReferenceContext(company, List.of(invoice)));
  }

  public record InvoiceWithEmail(InvoiceDto invoice, String dealerEmail, String companyName) {}

  @Transactional
  public InvoiceWithEmail getInvoiceWithDealerEmail(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    Invoice invoice =
        invoiceRepository
            .findByCompanyAndId(company, id)
            .orElseGet(() -> invoiceLookupService.requireInvoice(company, id));
    String dealerEmail = invoice.getDealer() != null ? invoice.getDealer().getEmail() : null;
    String companyName = invoice.getCompany() != null ? invoice.getCompany().getName() : null;
    return new InvoiceWithEmail(
        toDto(invoice, buildLinkedReferenceContext(company, List.of(invoice))),
        dealerEmail,
        companyName);
  }

  private InvoiceDto toDto(Invoice invoice) {
    return toDto(invoice, buildLinkedReferenceContext(invoice.getCompany(), List.of(invoice)));
  }

  private InvoiceDto toDto(Invoice invoice, LinkedReferenceContext linkedReferenceContext) {
    List<InvoiceLineDto> lineDtos =
        invoice.getLines().stream()
            .map(
                line ->
                    new InvoiceLineDto(
                        line.getId(),
                        line.getProductCode(),
                        line.getDescription(),
                        line.getQuantity(),
                        line.getUnitPrice(),
                        line.getTaxRate(),
                        line.getLineTotal(),
                        line.getTaxableAmount(),
                        line.getTaxAmount(),
                        line.getDiscountAmount(),
                        line.getCgstAmount(),
                        line.getSgstAmount(),
                        line.getIgstAmount()))
            .toList();
    DocumentLifecycleDto lifecycle =
        BusinessDocumentTruths.invoiceLifecycle(invoice.getStatus(), invoice.getJournalEntry());
    List<LinkedBusinessReferenceDto> linkedReferences =
        buildLinkedReferences(invoice, linkedReferenceContext);
    Dealer dealer = invoice.getDealer();
    return new InvoiceDto(
        invoice.getId(),
        invoice.getPublicId(),
        invoice.getInvoiceNumber(),
        invoice.getStatus(),
        invoice.getSubtotal(),
        invoice.getTaxTotal(),
        invoice.getTotalAmount(),
        invoice.getOutstandingAmount(),
        invoice.getCurrency(),
        invoice.getIssueDate(),
        invoice.getDueDate(),
        dealer != null ? dealer.getId() : null,
        dealer != null ? dealer.getName() : null,
        invoice.getSalesOrder() != null ? invoice.getSalesOrder().getId() : null,
        invoice.getJournalEntry() != null ? invoice.getJournalEntry().getId() : null,
        invoice.getCreatedAt(),
        lineDtos,
        lifecycle,
        linkedReferences);
  }

  private List<InvoiceDto> toDtos(Company company, List<Invoice> invoices) {
    if (invoices == null || invoices.isEmpty()) {
      return List.of();
    }
    LinkedReferenceContext linkedReferenceContext = buildLinkedReferenceContext(company, invoices);
    return invoices.stream().map(invoice -> toDto(invoice, linkedReferenceContext)).toList();
  }

  private List<LinkedBusinessReferenceDto> buildLinkedReferences(
      Invoice invoice, LinkedReferenceContext linkedReferenceContext) {
    List<LinkedBusinessReferenceDto> linkedReferences = new ArrayList<>();
    SalesOrder salesOrder = invoice.getSalesOrder();
    if (salesOrder != null) {
      List<PackagingSlip> slips =
          linkedReferenceContext
              .packagingSlipsBySalesOrderId()
              .getOrDefault(salesOrder.getId(), List.of());
      linkedReferences.add(
          BusinessDocumentTruths.reference(
              "SOURCE_ORDER",
              "SALES_ORDER",
              salesOrder.getId(),
              salesOrder.getOrderNumber(),
              BusinessDocumentTruths.salesOrderLifecycle(salesOrder),
              salesOrder.getSalesJournalEntryId()));
      for (PackagingSlip slip : slips) {
        if (!isSlipLinkedToInvoice(slip, invoice)) {
          continue;
        }
        linkedReferences.add(
            BusinessDocumentTruths.reference(
                "DISPATCH",
                "PACKAGING_SLIP",
                slip.getId(),
                slip.getSlipNumber(),
                BusinessDocumentTruths.packagingSlipLifecycle(slip),
                slip.getCogsJournalEntryId() != null
                    ? slip.getCogsJournalEntryId()
                    : slip.getJournalEntryId()));
      }
    }
    if (invoice.getJournalEntry() != null) {
      linkedReferences.add(
          BusinessDocumentTruths.reference(
              "ACCOUNTING_ENTRY",
              "JOURNAL_ENTRY",
              invoice.getJournalEntry().getId(),
              invoice.getJournalEntry().getReferenceNumber(),
              BusinessDocumentTruths.journalLifecycle(invoice.getJournalEntry()),
              invoice.getJournalEntry().getId()));
    }
    List<PartnerSettlementAllocation> settlementAllocations =
        linkedReferenceContext
            .settlementAllocationsByInvoiceId()
            .getOrDefault(invoice.getId(), List.of());
    if (settlementAllocations != null) {
      for (PartnerSettlementAllocation allocation : settlementAllocations) {
        linkedReferences.add(
            BusinessDocumentTruths.reference(
                "SETTLEMENT",
                "SETTLEMENT_ALLOCATION",
                allocation.getId(),
                allocation.getIdempotencyKey(),
                BusinessDocumentTruths.settlementLifecycle(allocation.getJournalEntry()),
                allocation.getJournalEntry() != null
                    ? allocation.getJournalEntry().getId()
                    : null));
      }
    }
    return linkedReferences.stream()
        .filter(reference -> reference.documentId() != null)
        .distinct()
        .toList();
  }

  private LinkedReferenceContext buildLinkedReferenceContext(
      Company company, List<Invoice> invoices) {
    if (company == null || invoices == null || invoices.isEmpty()) {
      return LinkedReferenceContext.empty();
    }
    List<Long> salesOrderIds =
        invoices.stream()
            .map(Invoice::getSalesOrder)
            .filter(Objects::nonNull)
            .map(SalesOrder::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<Long, List<PackagingSlip>> packagingSlipsBySalesOrderId =
        salesOrderIds.isEmpty()
            ? Map.of()
            : packagingSlipRepository
                .findAllByCompanyAndSalesOrderIdIn(company, salesOrderIds)
                .stream()
                .filter(
                    slip -> slip.getSalesOrder() != null && slip.getSalesOrder().getId() != null)
                .collect(Collectors.groupingBy(slip -> slip.getSalesOrder().getId()));
    List<Long> salesOrderIdsNeedingInvoiceCount =
        packagingSlipsBySalesOrderId.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .filter(entry -> !hasExplicitInvoiceLinks(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
    List<Invoice> salesOrderInvoices =
        salesOrderIdsNeedingInvoiceCount.isEmpty()
            ? List.of()
            : invoiceRepository.findByCompanyAndSalesOrder_IdIn(
                company, salesOrderIdsNeedingInvoiceCount);
    Map<Long, Integer> currentInvoiceCountsBySalesOrderId =
        salesOrderIdsNeedingInvoiceCount.isEmpty()
            ? Map.of()
            : salesOrderInvoices == null
                ? Map.of()
                : salesOrderInvoices.stream()
                    .filter(Objects::nonNull)
                    .filter(
                        orderInvoice ->
                            orderInvoice.getSalesOrder() != null
                                && orderInvoice.getSalesOrder().getId() != null)
                    .collect(
                        Collectors.groupingBy(
                            orderInvoice -> orderInvoice.getSalesOrder().getId(),
                            Collectors.collectingAndThen(
                                Collectors.toList(), InvoiceService::countCurrentInvoices)));

    List<Long> invoiceIds = invoices.stream().map(Invoice::getId).filter(Objects::nonNull).toList();
    Map<Long, List<PartnerSettlementAllocation>> settlementAllocationsByInvoiceId =
        invoiceIds.isEmpty()
            ? Map.of()
            : settlementAllocationRepository
                .findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, invoiceIds)
                .stream()
                .filter(
                    allocation ->
                        allocation.getInvoice() != null && allocation.getInvoice().getId() != null)
                .collect(Collectors.groupingBy(allocation -> allocation.getInvoice().getId()));

    return new LinkedReferenceContext(
        packagingSlipsBySalesOrderId,
        settlementAllocationsByInvoiceId,
        currentInvoiceCountsBySalesOrderId);
  }

  private boolean isSlipLinkedToInvoice(PackagingSlip slip, Invoice invoice) {
    return slip != null
        && invoice != null
        && slip.getInvoiceId() != null
        && invoice.getId() != null
        && slip.getInvoiceId().equals(invoice.getId());
  }

  private int defaultCurrentInvoiceCount(Invoice invoice) {
    return invoice != null && isCurrentInvoiceStatus(invoice.getStatus()) ? 1 : 0;
  }

  private static boolean hasExplicitInvoiceLinks(List<PackagingSlip> slips) {
    return slips != null
        && slips.stream().anyMatch(slip -> slip != null && slip.getInvoiceId() != null);
  }

  private static int countCurrentInvoices(List<Invoice> invoices) {
    if (invoices == null) {
      return 0;
    }
    return (int)
        invoices.stream()
            .filter(Objects::nonNull)
            .filter(invoice -> isCurrentInvoiceStatus(invoice.getStatus()))
            .count();
  }

  private static boolean isCurrentInvoiceStatus(String status) {
    if (status == null) {
      return true;
    }
    String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
    return !normalized.equals("DRAFT")
        && !normalized.equals("VOID")
        && !normalized.equals("REVERSED")
        && !normalized.equals("WRITTEN_OFF");
  }

  private record LinkedReferenceContext(
      Map<Long, List<PackagingSlip>> packagingSlipsBySalesOrderId,
      Map<Long, List<PartnerSettlementAllocation>> settlementAllocationsByInvoiceId,
      Map<Long, Integer> currentInvoiceCountsBySalesOrderId) {
    private static LinkedReferenceContext empty() {
      return new LinkedReferenceContext(Map.of(), Map.of(), Map.of());
    }
  }
}
