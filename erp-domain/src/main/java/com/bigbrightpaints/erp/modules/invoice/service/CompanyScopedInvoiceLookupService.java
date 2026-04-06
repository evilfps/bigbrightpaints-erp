package com.bigbrightpaints.erp.modules.invoice.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;

@Service
public class CompanyScopedInvoiceLookupService {

  private final CompanyEntityLookup legacyLookup;
  private final CompanyScopedLookupService companyScopedLookupService;
  private final InvoiceRepository invoiceRepository;

  @Autowired
  public CompanyScopedInvoiceLookupService(
      CompanyScopedLookupService companyScopedLookupService, InvoiceRepository invoiceRepository) {
    this.legacyLookup = null;
    this.companyScopedLookupService = companyScopedLookupService;
    this.invoiceRepository = invoiceRepository;
  }

  private CompanyScopedInvoiceLookupService(CompanyEntityLookup legacyLookup) {
    this.legacyLookup = legacyLookup;
    this.companyScopedLookupService = null;
    this.invoiceRepository = null;
  }

  public static CompanyScopedInvoiceLookupService fromLegacy(CompanyEntityLookup legacyLookup) {
    return new CompanyScopedInvoiceLookupService(legacyLookup);
  }

  public Invoice requireInvoice(Company company, Long invoiceId) {
    if (legacyLookup != null) {
      return legacyLookup.requireInvoice(company, invoiceId);
    }
    return companyScopedLookupService.require(
        company, invoiceId, invoiceRepository::findByCompanyAndId, "Invoice");
  }
}
