package com.bigbrightpaints.erp.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

  @Mock private JavaMailSender mailSender;

  @Mock private SpringTemplateEngine templateEngine;

  private EmailProperties emailProperties;
  private EmailService emailService;

  @BeforeEach
  void setUp() {
    emailProperties = new EmailProperties();
    emailProperties.setEnabled(true);
    emailProperties.setFromAddress("noreply@bigbrightpaints.com");
    emailProperties.setBaseUrl("https://example.com");
    emailService = new EmailService(mailSender, emailProperties, templateEngine);
  }

  @Test
  void sendSimpleEmailThrowsWhenSmtpSendFails() {
    doThrow(new MailSendException("smtp-failed"))
        .when(mailSender)
        .send(any(SimpleMailMessage.class));

    assertThatThrownBy(() -> emailService.sendSimpleEmail("user@example.com", "subject", "body"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR);
            });
  }

  @Test
  void sendInvoiceEmailThrowsWhenSmtpSendFails() {
    doThrow(new MailSendException("smtp-failed"))
        .when(mailSender)
        .send(any(MimeMessagePreparator.class));

    assertThatThrownBy(
            () ->
                emailService.sendInvoiceEmail(
                    "dealer@example.com",
                    "Dealer",
                    "INV-001",
                    "10 Feb 2026",
                    "20 Feb 2026",
                    "₹2000.00",
                    "Big Bright Paints",
                    "pdf".getBytes()))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR);
            });
  }

  @Test
  void sendSimpleEmailThrowsWhenMailDisabled() {
    emailProperties.setEnabled(false);

    assertThatThrownBy(() -> emailService.sendSimpleEmail("user@example.com", "subject", "body"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_CONFIGURATION_ERROR);
            });
  }

  @Test
  void sendInvoiceEmailThrowsWhenRecipientBlank() {
    assertThatThrownBy(
            () ->
                emailService.sendInvoiceEmail(
                    " ",
                    "Dealer",
                    "INV-001",
                    "10 Feb 2026",
                    "20 Feb 2026",
                    "₹2000.00",
                    "Big Bright Paints",
                    "pdf".getBytes()))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
            });
  }

  @Test
  void sendUserCredentialsEmailRequiredThrowsWhenSmtpSendFails() {
    doThrow(new MailSendException("smtp-failed"))
        .when(mailSender)
        .send(any(MimeMessagePreparator.class));

    assertThatThrownBy(
            () ->
                emailService.sendUserCredentialsEmailRequired(
                    "user@example.com", "User", "Temp@12345", "SKE"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR);
            });
  }

  @Test
  void sendUserCredentialsEmailRequiredThrowsWhenDeliveryDisabled() {
    emailProperties.setSendCredentials(false);

    assertThatThrownBy(
            () ->
                emailService.sendUserCredentialsEmailRequired(
                    "user@example.com", "User", "Temp@12345", "SKE"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_CONFIGURATION_ERROR);
            });
    verifyNoInteractions(mailSender);
  }

  @Test
  void isCredentialEmailDeliveryEnabled_returnsFalseWhenSmtpIsDisabled() {
    emailProperties.setEnabled(false);
    emailProperties.setSendCredentials(true);

    assertThat(emailService.isCredentialEmailDeliveryEnabled()).isFalse();
  }

  @Test
  void sendUserCredentialsEmailRequired_executesHtmlTemplateDeliveryPath() throws Exception {
    JavaMailSender localMailSender = org.mockito.Mockito.mock(JavaMailSender.class);
    StringTemplateResolver resolver = new StringTemplateResolver();
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCacheable(false);
    SpringTemplateEngine localTemplateEngine = new SpringTemplateEngine();
    localTemplateEngine.setTemplateResolver(resolver);
    EmailService localEmailService =
        new EmailService(localMailSender, emailProperties, localTemplateEngine);
    doAnswer(
            invocation -> {
              MimeMessagePreparator preparator = invocation.getArgument(0);
              MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
              preparator.prepare(mimeMessage);
              return null;
            })
        .when(localMailSender)
        .send(any(MimeMessagePreparator.class));

    assertThatCode(
            () ->
                localEmailService.sendUserCredentialsEmailRequired(
                    "user@example.com", "User", "Temp@12345", "   "))
        .doesNotThrowAnyException();
  }

  @Test
  void sendUserCredentialsEmailRequired_rejectsBlankRecipient() {
    assertThatThrownBy(
            () -> emailService.sendUserCredentialsEmailRequired("   ", "User", "Temp@12345", "SKE"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
            });
  }

  @Test
  void sendHtmlEmailRequired_throwsWhenGlobalMailDeliveryDisabled() {
    emailProperties.setEnabled(false);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    emailService,
                    "sendHtmlEmailRequired",
                    "user@example.com",
                    "Subject",
                    "mail/credentials",
                    new Context()))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_CONFIGURATION_ERROR);
            });
  }

  @Test
  void sendAdminEmailChangeVerificationRequired_formatsExpiryAsDeterministicUtcText()
      throws Exception {
    String formatted =
        ReflectionTestUtils.invokeMethod(
            emailService, "formatUtcTimestamp", Instant.parse("2026-03-27T12:34:56Z"));

    assertThat(formatted).isEqualTo("2026-03-27 12:34:56 UTC");
  }

  @Test
  void sendAdminEmailChangeVerificationRequired_usesHtmlTemplateWithUtcExpiry() throws Exception {
    JavaMailSender localMailSender = org.mockito.Mockito.mock(JavaMailSender.class);
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    resolver.setCacheable(false);
    SpringTemplateEngine localTemplateEngine = new SpringTemplateEngine();
    localTemplateEngine.setTemplateResolver(resolver);
    EmailService localEmailService =
        new EmailService(localMailSender, emailProperties, localTemplateEngine);
    MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
    doAnswer(
            invocation -> {
              MimeMessagePreparator preparator = invocation.getArgument(0);
              preparator.prepare(mimeMessage);
              return null;
            })
        .when(localMailSender)
        .send(any(MimeMessagePreparator.class));

    assertThatCode(
            () ->
                localEmailService.sendAdminEmailChangeVerificationRequired(
                    "new-admin@example.com",
                    "Admin User",
                    "ACME",
                    "verify-123",
                    Instant.parse("2026-03-27T12:34:56Z")))
        .doesNotThrowAnyException();

    Object content = mimeMessage.getContent();
    assertThat(content).isInstanceOf(MimeMultipart.class);
    Object bodyContent = ((MimeMultipart) content).getBodyPart(0).getContent();
    String body =
        bodyContent instanceof MimeMultipart multipart
            ? (String) multipart.getBodyPart(0).getContent()
            : (String) bodyContent;
    assertThat(body).contains("verify-123");
    assertThat(body).contains("2026-03-27 12:34:56 UTC");
    verify(localMailSender).send(any(MimeMessagePreparator.class));
  }

  @Test
  void sendAdminEmailChangeVerificationRequired_failsClosedWhenCredentialDeliveryDisabled() {
    emailProperties.setSendCredentials(false);

    assertThatThrownBy(
            () ->
                emailService.sendAdminEmailChangeVerificationRequired(
                    "new-admin@example.com",
                    "Admin User",
                    "ACME",
                    "verify-123",
                    Instant.parse("2026-03-27T12:34:56Z")))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_CONFIGURATION_ERROR);
            });
  }

  @Test
  void formatUtcTimestamp_returnsNullWhenTimestampMissing() {
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    emailService, "formatUtcTimestamp", (Instant) null))
        .isNull();
  }
}
