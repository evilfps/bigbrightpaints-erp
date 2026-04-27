package com.bigbrightpaints.erp.modules.accounting.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface PartnerPaymentEventRepository extends JpaRepository<PartnerPaymentEvent, Long> {

  Optional<PartnerPaymentEvent> findByCompanyAndIdempotencyKeyIgnoreCase(
      Company company, String idempotencyKey);

  Optional<PartnerPaymentEvent> findByCompanyAndPaymentFlowAndReferenceNumberIgnoreCase(
      Company company, PartnerPaymentFlow paymentFlow, String referenceNumber);

  List<PartnerPaymentEvent> findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAsc(
      Company company, String idempotencyKey);

  Optional<PartnerPaymentEvent> findByCompanyAndJournalEntry(
      Company company, JournalEntry journalEntry);
}
