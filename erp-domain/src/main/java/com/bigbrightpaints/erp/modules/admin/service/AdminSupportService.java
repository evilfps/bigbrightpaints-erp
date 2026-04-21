package com.bigbrightpaints.erp.modules.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketCreateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketResponse;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
public class AdminSupportService {

  private final SupportTicketRepository supportTicketRepository;
  private final CompanyContextService companyContextService;
  private final SupportTicketAccessSupport supportTicketAccessSupport;

  public AdminSupportService(
      SupportTicketRepository supportTicketRepository,
      CompanyContextService companyContextService,
      SupportTicketAccessSupport supportTicketAccessSupport) {
    this.supportTicketRepository = supportTicketRepository;
    this.companyContextService = companyContextService;
    this.supportTicketAccessSupport = supportTicketAccessSupport;
  }

  @Transactional
  public SupportTicketResponse create(SupportTicketCreateRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    return supportTicketAccessSupport.createTicket(company, request);
  }

  @Transactional(readOnly = true)
  public List<SupportTicketResponse> listAllTenantTickets() {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount actor = supportTicketAccessSupport.requireCurrentUser();
    List<SupportTicket> tickets =
        supportTicketRepository.findByCompanyOrderByCreatedAtDesc(company);
    return supportTicketAccessSupport.toResponses(tickets, actor.getId());
  }

  @Transactional(readOnly = true)
  public SupportTicketResponse getById(Long ticketId) {
    Long resolvedTicketId = supportTicketAccessSupport.requireTicketId(ticketId);
    Company company = companyContextService.requireCurrentCompany();
    UserAccount actor = supportTicketAccessSupport.requireCurrentUser();
    SupportTicket ticket =
        supportTicketRepository
            .findByCompanyAndId(company, resolvedTicketId)
            .orElseThrow(() -> supportTicketAccessSupport.notFound(resolvedTicketId));
    return supportTicketAccessSupport.toResponses(List.of(ticket), actor.getId()).getFirst();
  }
}
