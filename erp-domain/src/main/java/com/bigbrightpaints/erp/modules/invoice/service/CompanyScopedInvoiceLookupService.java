package com.bigbrightpaints.erp.modules.invoice.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;

@Service
public class CompanyScopedInvoiceLookupService {

  private final CompanyScopedLookupService companyScopedLookupService;
  private final InvoiceRepository invoiceRepository;

  @Autowired
  public CompanyScopedInvoiceLookupService(
      CompanyScopedLookupService companyScopedLookupService, InvoiceRepository invoiceRepository) {
    this.companyScopedLookupService = companyScopedLookupService;
    this.invoiceRepository = invoiceRepository;
  }

  public Invoice requireInvoice(Company company, Long invoiceId) {
    return companyScopedLookupService.require(
        company, invoiceId, invoiceRepository::findByCompanyAndId, "Invoice");
  }

  public Invoice requireInvoicePdf(Company company, Long invoiceId) {
    return companyScopedLookupService.require(
        company, invoiceId, invoiceRepository::findPdfViewByCompanyAndId, "Invoice");
  }
}
