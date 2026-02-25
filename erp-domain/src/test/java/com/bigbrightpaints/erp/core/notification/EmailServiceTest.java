package com.bigbrightpaints.erp.core.notification;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
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
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private SpringTemplateEngine templateEngine;

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
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailService.sendSimpleEmail("user@example.com", "subject", "body"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR);
                });
    }

    @Test
    void sendInvoiceEmailThrowsWhenSmtpSendFails() {
        doThrow(new MailSendException("smtp-failed"))
                .when(mailSender).send(any(MimeMessagePreparator.class));

        assertThatThrownBy(() -> emailService.sendInvoiceEmail(
                "dealer@example.com",
                "Dealer",
                "INV-001",
                "10 Feb 2026",
                "20 Feb 2026",
                "₹2000.00",
                "Big Bright Paints",
                "pdf".getBytes()
        ))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR);
                });
    }

    @Test
    void sendSimpleEmailThrowsWhenMailDisabled() {
        emailProperties.setEnabled(false);

        assertThatThrownBy(() -> emailService.sendSimpleEmail("user@example.com", "subject", "body"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_CONFIGURATION_ERROR);
                });
    }

    @Test
    void sendInvoiceEmailThrowsWhenRecipientBlank() {
        assertThatThrownBy(() -> emailService.sendInvoiceEmail(
                " ",
                "Dealer",
                "INV-001",
                "10 Feb 2026",
                "20 Feb 2026",
                "₹2000.00",
                "Big Bright Paints",
                "pdf".getBytes()
        ))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                });
    }

    @Test
    void sendUserCredentialsEmail_bestEffortDoesNotThrowWhenSmtpSendFails() {
        doThrow(new MailSendException("smtp-failed"))
                .when(mailSender).send(any(MimeMessagePreparator.class));

        assertThatCode(() -> emailService.sendUserCredentialsEmail(
                "user@example.com",
                "User",
                "Temp@12345",
                "SKE")).doesNotThrowAnyException();
    }

    @Test
    void sendUserCredentialsEmail_bestEffortSkipsWhenDeliveryDisabled() {
        emailProperties.setSendCredentials(false);

        assertThatCode(() -> emailService.sendUserCredentialsEmail(
                "user@example.com",
                "User",
                "Temp@12345",
                "SKE")).doesNotThrowAnyException();
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendUserCredentialsEmailRequiredThrowsWhenSmtpSendFails() {
        doThrow(new MailSendException("smtp-failed"))
                .when(mailSender).send(any(MimeMessagePreparator.class));

        assertThatThrownBy(() -> emailService.sendUserCredentialsEmailRequired(
                "user@example.com",
                "User",
                "Temp@12345",
                "SKE"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR);
                });
    }

    @Test
    void sendUserCredentialsEmailRequiredThrowsWhenDeliveryDisabled() {
        emailProperties.setSendCredentials(false);

        assertThatThrownBy(() -> emailService.sendUserCredentialsEmailRequired(
                "user@example.com",
                "User",
                "Temp@12345",
                "SKE"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
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
        EmailService localEmailService = new EmailService(localMailSender, emailProperties, localTemplateEngine);
        doAnswer(invocation -> {
            MimeMessagePreparator preparator = invocation.getArgument(0);
            MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
            preparator.prepare(mimeMessage);
            return null;
        }).when(localMailSender).send(any(MimeMessagePreparator.class));

        assertThatCode(() -> localEmailService.sendUserCredentialsEmailRequired(
                "user@example.com",
                "User",
                "Temp@12345",
                "   ")).doesNotThrowAnyException();
    }

    @Test
    void sendUserCredentialsEmailRequired_rejectsBlankRecipient() {
        assertThatThrownBy(() -> emailService.sendUserCredentialsEmailRequired(
                "   ",
                "User",
                "Temp@12345",
                "SKE"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                });
    }

    @Test
    void sendHtmlEmailRequired_throwsWhenGlobalMailDeliveryDisabled() {
        emailProperties.setEnabled(false);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                emailService,
                "sendHtmlEmailRequired",
                "user@example.com",
                "Subject",
                "mail/credentials",
                new Context()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_CONFIGURATION_ERROR);
                });
    }
}
