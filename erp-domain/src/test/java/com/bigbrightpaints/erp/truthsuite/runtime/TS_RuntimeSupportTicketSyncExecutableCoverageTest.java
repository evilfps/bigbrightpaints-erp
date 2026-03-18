package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.config.GitHubProperties;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketCategory;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketStatus;
import com.bigbrightpaints.erp.modules.admin.service.GitHubIssueClient;
import com.bigbrightpaints.erp.modules.admin.service.SupportTicketGitHubSyncService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.context.Context;

@Tag("critical")
class TS_RuntimeSupportTicketSyncExecutableCoverageTest {

    private static void installCompanyTime(Instant now) {
        CompanyClock companyClock = org.mockito.Mockito.mock(CompanyClock.class);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        when(companyClock.now(any())).thenReturn(now);
        when(companyClock.now(null)).thenReturn(now);
        when(companyClock.today(any())).thenReturn(today);
        when(companyClock.today(null)).thenReturn(today);
        new CompanyTime(companyClock);
    }

    @Test
    void submitGitHubIssueAsync_mapsSupportCategoryToSupportLabel() {
        SupportTicketRepository supportTicketRepository = org.mockito.Mockito.mock(SupportTicketRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        GitHubIssueClient gitHubIssueClient = org.mockito.Mockito.mock(GitHubIssueClient.class);
        EmailService emailService = org.mockito.Mockito.mock(EmailService.class);

        SupportTicketGitHubSyncService service = new SupportTicketGitHubSyncService(
                supportTicketRepository,
                userAccountRepository,
                gitHubIssueClient,
                emailService);

        SupportTicket ticket = ticket(1001L, 71L, "Support label expected");
        ticket.setCategory(SupportTicketCategory.SUPPORT);
        when(supportTicketRepository.findById(1001L)).thenReturn(Optional.of(ticket));
        when(gitHubIssueClient.isEnabledAndConfigured()).thenReturn(true);
        when(gitHubIssueClient.createIssue(anyString(), anyString(), any())).thenReturn(
                new GitHubIssueClient.GitHubIssueCreateResult(
                        4321L,
                        "https://github.com/acme/repo/issues/4321",
                        "OPEN",
                        Instant.parse("2026-03-04T05:00:00Z")));

        service.submitGitHubIssueAsync(1001L);

        ArgumentCaptor<List<String>> labelsCaptor = ArgumentCaptor.forClass(List.class);
        verify(gitHubIssueClient).createIssue(anyString(), anyString(), labelsCaptor.capture());
        assertThat(labelsCaptor.getValue()).containsExactly("support");
        verify(supportTicketRepository).save(ticket);
    }

    @Test
    void submitGitHubIssueAsync_mapsBugCategoryToBugLabel() {
        SupportTicketRepository supportTicketRepository = org.mockito.Mockito.mock(SupportTicketRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        GitHubIssueClient gitHubIssueClient = org.mockito.Mockito.mock(GitHubIssueClient.class);
        EmailService emailService = org.mockito.Mockito.mock(EmailService.class);

        SupportTicketGitHubSyncService service = new SupportTicketGitHubSyncService(
                supportTicketRepository,
                userAccountRepository,
                gitHubIssueClient,
                emailService);

        SupportTicket ticket = ticket(1002L, 71L, "Bug label expected");
        ticket.setCategory(SupportTicketCategory.BUG);
        when(supportTicketRepository.findById(1002L)).thenReturn(Optional.of(ticket));
        when(gitHubIssueClient.isEnabledAndConfigured()).thenReturn(true);
        when(gitHubIssueClient.createIssue(anyString(), anyString(), any())).thenReturn(
                new GitHubIssueClient.GitHubIssueCreateResult(
                        4322L,
                        "https://github.com/acme/repo/issues/4322",
                        "OPEN",
                        Instant.parse("2026-03-04T05:00:00Z")));

        service.submitGitHubIssueAsync(1002L);

        ArgumentCaptor<List<String>> labelsCaptor = ArgumentCaptor.forClass(List.class);
        verify(gitHubIssueClient).createIssue(anyString(), anyString(), labelsCaptor.capture());
        assertThat(labelsCaptor.getValue()).containsExactly("bug");
        verify(supportTicketRepository).save(ticket);
    }

    @Test
    void submitGitHubIssueAsync_mapsFeatureRequestCategoryToEnhancementLabel() {
        SupportTicketRepository supportTicketRepository = org.mockito.Mockito.mock(SupportTicketRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        GitHubIssueClient gitHubIssueClient = org.mockito.Mockito.mock(GitHubIssueClient.class);
        EmailService emailService = org.mockito.Mockito.mock(EmailService.class);

        SupportTicketGitHubSyncService service = new SupportTicketGitHubSyncService(
                supportTicketRepository,
                userAccountRepository,
                gitHubIssueClient,
                emailService);

        SupportTicket ticket = ticket(1003L, 71L, "Feature label expected");
        ticket.setCategory(SupportTicketCategory.FEATURE_REQUEST);
        when(supportTicketRepository.findById(1003L)).thenReturn(Optional.of(ticket));
        when(gitHubIssueClient.isEnabledAndConfigured()).thenReturn(true);
        when(gitHubIssueClient.createIssue(anyString(), anyString(), any())).thenReturn(
                new GitHubIssueClient.GitHubIssueCreateResult(
                        4323L,
                        "https://github.com/acme/repo/issues/4323",
                        "OPEN",
                        Instant.parse("2026-03-04T05:00:00Z")));

        service.submitGitHubIssueAsync(1003L);

        ArgumentCaptor<List<String>> labelsCaptor = ArgumentCaptor.forClass(List.class);
        verify(gitHubIssueClient).createIssue(anyString(), anyString(), labelsCaptor.capture());
        assertThat(labelsCaptor.getValue()).containsExactly("enhancement");
        verify(supportTicketRepository).save(ticket);
    }

    @Test
    void submitGitHubIssueAsync_handlesDisabledIntegrationGracefully() {
        SupportTicketRepository supportTicketRepository = org.mockito.Mockito.mock(SupportTicketRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        GitHubIssueClient gitHubIssueClient = org.mockito.Mockito.mock(GitHubIssueClient.class);
        EmailService emailService = org.mockito.Mockito.mock(EmailService.class);

        SupportTicketGitHubSyncService service = new SupportTicketGitHubSyncService(
                supportTicketRepository,
                userAccountRepository,
                gitHubIssueClient,
                emailService);

        SupportTicket ticket = ticket(1101L, 71L, "Disabled sync");
        when(supportTicketRepository.findById(1101L)).thenReturn(Optional.of(ticket));
        when(gitHubIssueClient.isEnabledAndConfigured()).thenReturn(false);

        service.submitGitHubIssueAsync(1101L);

        assertThat(ticket.getGithubLastError()).contains("disabled");
        verify(supportTicketRepository).save(ticket);
    }

    @Test
    void createIssuePayload_containsMappedLabels() {
        installCompanyTime(Instant.parse("2026-03-04T05:00:00Z"));
        GitHubProperties gitHubProperties = new GitHubProperties();
        gitHubProperties.setEnabled(true);
        gitHubProperties.setToken("token");
        gitHubProperties.setRepoOwner("acme");
        gitHubProperties.setRepoName("repo");

        org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder =
                org.mockito.Mockito.mock(org.springframework.boot.web.client.RestTemplateBuilder.class);
        org.springframework.web.client.RestTemplate restTemplate =
                org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(
                eq("https://api.github.com/repos/acme/repo/issues"),
                eq(org.springframework.http.HttpMethod.POST),
                any(org.springframework.http.HttpEntity.class),
                eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(
                        "{\"number\":99,\"html_url\":\"https://github.com/acme/repo/issues/99\",\"state\":\"open\"}"));

        GitHubIssueClient gitHubIssueClient = new GitHubIssueClient(
                gitHubProperties,
                restTemplateBuilder,
                new com.fasterxml.jackson.databind.ObjectMapper());

        List<String> labels = List.of("bug");
        gitHubIssueClient.createIssue("Title", "Body", labels);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<org.springframework.http.HttpEntity<Map<String, Object>>> requestCaptor =
                ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://api.github.com/repos/acme/repo/issues"),
                eq(org.springframework.http.HttpMethod.POST),
                requestCaptor.capture(),
                eq(String.class));

        Map<String, Object> payload = requestCaptor.getValue().getBody();
        assertThat(payload)
                .containsEntry("title", "Title")
                .containsEntry("body", "Body")
                .containsEntry("labels", labels);
    }

    @Test
    void syncGithubStatus_closedIssue_marksResolvedAndSendsNotification() {
        installCompanyTime(Instant.parse("2026-03-04T05:00:00Z"));
        SupportTicketRepository supportTicketRepository = org.mockito.Mockito.mock(SupportTicketRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        GitHubIssueClient gitHubIssueClient = org.mockito.Mockito.mock(GitHubIssueClient.class);
        EmailService emailService = org.mockito.Mockito.mock(EmailService.class);

        SupportTicketGitHubSyncService service = new SupportTicketGitHubSyncService(
                supportTicketRepository,
                userAccountRepository,
                gitHubIssueClient,
                emailService);

        SupportTicket ticket = ticket(1201L, 72L, "Resolution expected");
        ticket.setGithubIssueNumber(4567L);
        ticket.setStatus(SupportTicketStatus.OPEN);

        UserAccount requester = new UserAccount("requester@acme.com", "hash", "Requester");
        ReflectionTestUtils.setField(requester, "id", 72L);

        when(gitHubIssueClient.isEnabledAndConfigured()).thenReturn(true);
        when(supportTicketRepository.findTop200ByGithubIssueNumberIsNotNullAndStatusInOrderByCreatedAtAsc(
                List.of(SupportTicketStatus.OPEN, SupportTicketStatus.IN_PROGRESS))).thenReturn(List.of(ticket));
        when(gitHubIssueClient.fetchIssueState(4567L)).thenReturn(
                new GitHubIssueClient.GitHubIssueStateResult(
                        4567L,
                        "https://github.com/acme/repo/issues/4567",
                        "closed",
                        Instant.parse("2026-03-04T05:00:00Z")));
        when(userAccountRepository.findById(72L)).thenReturn(Optional.of(requester));

        service.syncGitHubIssueStatuses();

        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.RESOLVED);
        assertThat(ticket.getResolvedAt()).isNotNull();
        assertThat(ticket.getResolvedNotificationSentAt()).isNotNull();
        assertThat(ticket.getGithubIssueState()).isEqualTo("CLOSED");
        verify(emailService).sendTemplatedEmailRequired(
                eq("requester@acme.com"),
                eq("Support ticket resolved - #1201"),
                eq("mail/ticket-resolved"),
                any(Context.class));
        verify(supportTicketRepository).save(ticket);
    }

    private SupportTicket ticket(Long id, Long userId, String subject) {
        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 901L);
        company.setCode("ACME");

        SupportTicket ticket = new SupportTicket();
        ReflectionTestUtils.setField(ticket, "id", id);
        ticket.setCompany(company);
        ticket.setUserId(userId);
        ticket.setCategory(SupportTicketCategory.SUPPORT);
        ticket.setSubject(subject);
        ticket.setDescription("Issue description");
        ticket.setStatus(SupportTicketStatus.OPEN);
        return ticket;
    }
}
