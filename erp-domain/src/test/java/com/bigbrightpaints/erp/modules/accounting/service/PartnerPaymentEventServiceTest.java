package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentEvent;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentEventRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentFlow;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
class PartnerPaymentEventServiceTest {

  @Mock private PartnerPaymentEventRepository repository;

  private PartnerPaymentEventService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service = new PartnerPaymentEventService(repository);
    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 44L);
    company.setBaseCurrency(" inr ");
  }

  @Test
  void resolveOrCreateSupplierPaymentEvent_createsCanonicalEvent() {
    Supplier supplier = supplier(801L);
    when(repository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAsc(company, "PAY-1"))
        .thenReturn(List.of());
    when(repository.findByCompanyAndPaymentFlowAndReferenceNumberIgnoreCase(
            company, PartnerPaymentFlow.SUPPLIER_SETTLEMENT, "SUP-1"))
        .thenReturn(Optional.empty());
    when(repository.save(any(PartnerPaymentEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PartnerPaymentEvent event =
        service.resolveOrCreateSupplierPaymentEvent(
            company,
            supplier,
            PartnerPaymentFlow.SUPPLIER_SETTLEMENT,
            new BigDecimal("125.50"),
            LocalDate.of(2026, 4, 1),
            " SUP-1 ",
            "PAY-1",
            " settlement ",
            "/supplier");

    assertThat(event.getPartnerType()).isEqualTo(PartnerType.SUPPLIER);
    assertThat(event.getSupplier()).isSameAs(supplier);
    assertThat(event.getDealer()).isNull();
    assertThat(event.getReferenceNumber()).isEqualTo("SUP-1");
    assertThat(event.getAmount()).isEqualByComparingTo("125.50");
    assertThat(event.getCurrency()).isEqualTo("INR");
    assertThat(event.getMemo()).isEqualTo("settlement");
    verify(repository).save(event);
  }

  @Test
  void resolveOrCreateDealerPaymentEvent_replaysByReferenceWithoutIdempotencyKeyMatch() {
    Dealer dealer = dealer(701L);
    PartnerPaymentEvent existing = dealerEvent(dealer, "OLD-KEY", "DR-9", "receipt");
    when(repository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAsc(company, "NEW-KEY"))
        .thenReturn(List.of());
    when(repository.findByCompanyAndPaymentFlowAndReferenceNumberIgnoreCase(
            company, PartnerPaymentFlow.DEALER_RECEIPT, "DR-9"))
        .thenReturn(Optional.of(existing));

    PartnerPaymentEvent event =
        service.resolveOrCreateDealerPaymentEvent(
            company,
            dealer,
            PartnerPaymentFlow.DEALER_RECEIPT,
            new BigDecimal("75.00"),
            LocalDate.of(2026, 4, 2),
            "DR-9",
            "NEW-KEY",
            "receipt",
            "/dealer");

    assertThat(event).isSameAs(existing);
  }

  @Test
  void resolveOrCreateDealerPaymentEvent_rejectsDifferentReplayPartner() {
    Dealer requestedDealer = dealer(701L);
    PartnerPaymentEvent existing = dealerEvent(dealer(702L), "KEY-1", "DR-9", "receipt");
    when(repository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAsc(company, "KEY-1"))
        .thenReturn(List.of(existing));

    assertThatThrownBy(
            () ->
                service.resolveOrCreateDealerPaymentEvent(
                    company,
                    requestedDealer,
                    PartnerPaymentFlow.DEALER_RECEIPT,
                    new BigDecimal("75.00"),
                    LocalDate.of(2026, 4, 2),
                    "DR-9",
                    "KEY-1",
                    "receipt",
                    "/dealer"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different dealer payment event");
  }

  @Test
  void linkJournalEntry_rejectsDifferentExistingJournal() {
    PartnerPaymentEvent event = dealerEvent(dealer(701L), "KEY-1", "DR-9", "receipt");
    event.setJournalEntry(journalEntry(10L));

    assertThatThrownBy(() -> service.linkJournalEntry(event, journalEntry(11L)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already linked to a different journal entry");
  }

  private PartnerPaymentEvent dealerEvent(
      Dealer dealer, String idempotencyKey, String referenceNumber, String memo) {
    PartnerPaymentEvent event = new PartnerPaymentEvent();
    event.setCompany(company);
    event.setPartnerType(PartnerType.DEALER);
    event.setDealer(dealer);
    event.setPaymentFlow(PartnerPaymentFlow.DEALER_RECEIPT);
    event.setSourceRoute("/dealer");
    event.setReferenceNumber(referenceNumber);
    event.setIdempotencyKey(idempotencyKey);
    event.setPaymentDate(LocalDate.of(2026, 4, 2));
    event.setAmount(new BigDecimal("75.00"));
    event.setMemo(memo);
    return event;
  }

  private Dealer dealer(Long id) {
    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", id);
    return dealer;
  }

  private Supplier supplier(Long id) {
    Supplier supplier = new Supplier();
    ReflectionFieldAccess.setField(supplier, "id", id);
    return supplier;
  }

  private JournalEntry journalEntry(Long id) {
    JournalEntry entry = new JournalEntry();
    ReflectionFieldAccess.setField(entry, "id", id);
    return entry;
  }
}
