package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketCategory;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketStatus;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketCreateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketResponse;
import com.bigbrightpaints.erp.modules.admin.service.SupportTicketGitHubSyncService;
import com.bigbrightpaints.erp.modules.admin.service.SupportTicketService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;

@Tag("critical")
class TS_RuntimeSupportTicketServiceExecutableCoverageTest {

  private SupportTicketRepository supportTicketRepository;
  private CompanyContextService companyContextService;
  private SupportTicketGitHubSyncService supportTicketGitHubSyncService;
  private SupportTicketService supportTicketService;
  private Company company;

  @BeforeEach
  void setUp() {
    supportTicketRepository = Mockito.mock(SupportTicketRepository.class);
    companyContextService = Mockito.mock(CompanyContextService.class);
    supportTicketGitHubSyncService = Mockito.mock(SupportTicketGitHubSyncService.class);

    supportTicketService =
        new SupportTicketService(
            supportTicketRepository, companyContextService, supportTicketGitHubSyncService);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 501L);
    company.setCode("ACME");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void create_savesTicketAndTriggersGithubSubmission() {
    UserAccount requester = user(71L, "requester@acme.com", "ROLE_SALES", company);
    authenticate(requester);

    when(supportTicketRepository.save(any(SupportTicket.class)))
        .thenAnswer(
            invocation -> {
              SupportTicket ticket = invocation.getArgument(0);
              ReflectionTestUtils.setField(ticket, "id", 8801L);
              return ticket;
            });

    SupportTicketResponse response =
        supportTicketService.create(
            new SupportTicketCreateRequest(
                "support", "Unable to export", "Export request fails with timeout"));

    assertThat(response.id()).isEqualTo(8801L);
    assertThat(response.userId()).isEqualTo(71L);
    assertThat(response.companyCode()).isEqualTo("ACME");
    assertThat(response.category()).isEqualTo(SupportTicketCategory.SUPPORT);
    verify(supportTicketRepository).save(any(SupportTicket.class));
    verify(supportTicketGitHubSyncService).submitGitHubIssueAsync(8801L);
  }

  @Test
  void list_usesGlobalScopeForSuperAdmin() {
    UserAccount superAdmin = user(81L, "super@root.com", "ROLE_SUPER_ADMIN", company);
    authenticate(superAdmin);

    SupportTicket ticketA = ticket(9201L, company, 71L, "ACME ticket");
    SupportTicket ticketB = ticket(9202L, company, 72L, "Another ACME ticket");
    when(supportTicketRepository.findAllByOrderByCreatedAtDesc())
        .thenReturn(List.of(ticketA, ticketB));
    when(supportTicketRepository.findUsersByIdIn(
            argThat(ids -> ids != null && ids.containsAll(List.of(71L, 72L)))))
        .thenReturn(
            List.of(
                user(71L, "requester1@acme.com", "ROLE_SALES", company),
                user(72L, "requester2@acme.com", "ROLE_SALES", company)));

    List<SupportTicketResponse> responses = supportTicketService.list();

    assertThat(responses).hasSize(2);
    verify(supportTicketRepository).findAllByOrderByCreatedAtDesc();
    verify(supportTicketRepository)
        .findUsersByIdIn(argThat(ids -> ids != null && ids.containsAll(List.of(71L, 72L))));
    verify(supportTicketRepository, never()).findByCompanyOrderByCreatedAtDesc(any());
    verify(supportTicketRepository, never())
        .findByCompanyAndUserIdOrderByCreatedAtDesc(any(), any());
  }

  @Test
  void list_usesCompanyScopeForAdmin() {
    UserAccount admin = user(82L, "admin@acme.com", "ROLE_ADMIN", company);
    authenticate(admin);

    SupportTicket ticket = ticket(9301L, company, 71L, "Scoped ACME ticket");
    when(supportTicketRepository.findByCompanyOrderByCreatedAtDesc(company))
        .thenReturn(List.of(ticket));
    when(supportTicketRepository.findUsersByIdIn(argThat(ids -> ids != null && ids.contains(71L))))
        .thenReturn(List.of(user(71L, "requester@acme.com", "ROLE_SALES", company)));

    List<SupportTicketResponse> responses = supportTicketService.list();

    assertThat(responses).hasSize(1);
    assertThat(responses.getFirst().id()).isEqualTo(9301L);
    verify(supportTicketRepository).findByCompanyOrderByCreatedAtDesc(company);
    verify(supportTicketRepository, never()).findAllByOrderByCreatedAtDesc();
    verify(supportTicketRepository, never())
        .findByCompanyAndUserIdOrderByCreatedAtDesc(any(), any());
  }

  @Test
  void list_usesSelfScopeForNonAdminUsers() {
    UserAccount sales = user(83L, "sales@acme.com", "ROLE_SALES", company);
    authenticate(sales);

    SupportTicket ownTicket = ticket(9401L, company, 83L, "Own ticket");
    when(supportTicketRepository.findByCompanyAndUserIdOrderByCreatedAtDesc(company, 83L))
        .thenReturn(List.of(ownTicket));

    List<SupportTicketResponse> responses = supportTicketService.list();

    assertThat(responses).hasSize(1);
    assertThat(responses.getFirst().id()).isEqualTo(9401L);
    verify(supportTicketRepository).findByCompanyAndUserIdOrderByCreatedAtDesc(company, 83L);
    verify(supportTicketRepository, never()).findAllByOrderByCreatedAtDesc();
    verify(supportTicketRepository, never()).findByCompanyOrderByCreatedAtDesc(any());
  }

  private void authenticate(UserAccount userAccount) {
    UserPrincipal principal = new UserPrincipal(userAccount);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private UserAccount user(Long id, String email, String roleName, Company scopedCompany) {
    UserAccount user = new UserAccount(email, "hash", email);
    ReflectionTestUtils.setField(user, "id", id);
    if (scopedCompany != null) {
      user.setCompany(scopedCompany);
    }
    Role role = new Role();
    role.setName(roleName);
    user.addRole(role);
    return user;
  }

  private SupportTicket ticket(Long id, Company scopedCompany, Long userId, String subject) {
    SupportTicket ticket = new SupportTicket();
    ReflectionTestUtils.setField(ticket, "id", id);
    ticket.setCompany(scopedCompany);
    ticket.setUserId(userId);
    ticket.setCategory(SupportTicketCategory.SUPPORT);
    ticket.setSubject(subject);
    ticket.setDescription("description");
    ticket.setStatus(SupportTicketStatus.OPEN);
    return ticket;
  }
}
