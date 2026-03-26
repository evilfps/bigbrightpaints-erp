package com.bigbrightpaints.erp.core.notification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;

@Service
public class EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailService.class);
  private static final DateTimeFormatter UTC_EMAIL_TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  private final JavaMailSender mailSender;
  private final EmailProperties properties;
  private final SpringTemplateEngine templateEngine;

  public EmailService(
      JavaMailSender mailSender, EmailProperties properties, SpringTemplateEngine templateEngine) {
    this.mailSender = mailSender;
    this.properties = properties;
    this.templateEngine = templateEngine;
  }

  public void sendSimpleEmail(String to, String subject, String body) {
    if (!properties.isEnabled()) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR,
          "Email delivery is disabled; enable erp.mail.enabled and SMTP settings");
    }
    if (!StringUtils.hasText(to)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Email recipient is required");
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setTo(to);
    message.setFrom(properties.getFromAddress());
    message.setSubject(subject);
    message.setText(body);
    try {
      mailSender.send(message);
      log.info("Sent email to {}", to);
    } catch (MailException ex) {
      log.error("Failed to send email to {}: {}", to, ex.getMessage(), ex);
      throw new ApplicationException(
              ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR, "Failed to dispatch email via SMTP", ex)
          .withDetail("recipient", to);
    }
  }

  public void sendUserCredentialsEmail(String to, String displayName, String password) {
    sendUserCredentialsEmail(to, displayName, password, null);
  }

  public boolean isPasswordResetEmailDeliveryEnabled() {
    return properties.isEnabled() && properties.isSendPasswordReset();
  }

  public boolean isCredentialEmailDeliveryEnabled() {
    return properties.isEnabled() && properties.isSendCredentials();
  }

  public void sendUserCredentialsEmail(
      String to, String displayName, String password, String companyCode) {
    if (!properties.isSendCredentials()) {
      log.debug("Credential email sending disabled. Skipping for {}", to);
      return;
    }
    String subject = "Your BigBright ERP account credentials";
    Context context = new Context();
    context.setVariable("displayName", displayName);
    context.setVariable("email", to);
    context.setVariable("temporaryPassword", password);
    context.setVariable(
        "companyCode", StringUtils.hasText(companyCode) ? companyCode.trim() : null);
    context.setVariable("loginUrl", properties.getBaseUrl());
    context.setVariable("preheader", "Your Orchestrator ERP account is ready.");
    sendHtmlEmail(to, subject, "mail/credentials", context);
  }

  public void sendUserCredentialsEmailRequired(
      String to, String displayName, String password, String companyCode) {
    if (!isCredentialEmailDeliveryEnabled()) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR,
          "Credential email delivery is disabled; enable erp.mail.enabled=true and"
              + " erp.mail.send-credentials=true");
    }
    String subject = "Your BigBright ERP account credentials";
    Context context = new Context();
    context.setVariable("displayName", displayName);
    context.setVariable("email", to);
    context.setVariable("temporaryPassword", password);
    context.setVariable(
        "companyCode", StringUtils.hasText(companyCode) ? companyCode.trim() : null);
    context.setVariable("loginUrl", properties.getBaseUrl());
    context.setVariable("preheader", "Your Orchestrator ERP account is ready.");
    sendHtmlEmailRequired(to, subject, "mail/credentials", context);
  }

  public void sendPasswordResetEmail(String to, String displayName, String resetToken) {
    if (!properties.isSendPasswordReset()) {
      log.debug("Password reset email sending disabled. Skipping for {}", to);
      return;
    }
    sendPasswordResetEmailInternal(to, displayName, resetToken, false);
  }

  public void sendPasswordResetEmailRequired(String to, String displayName, String resetToken) {
    if (!isPasswordResetEmailDeliveryEnabled()) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR,
          "Password reset email delivery is disabled; enable erp.mail.enabled=true and"
              + " erp.mail.send-password-reset=true");
    }
    sendPasswordResetEmailInternal(to, displayName, resetToken, true);
  }

  private void sendPasswordResetEmailInternal(
      String to, String displayName, String resetToken, boolean required) {
    String resetLink = properties.getBaseUrl() + "/reset-password?token=" + resetToken;
    String subject = "Reset your BigBright ERP password";
    Context context = new Context();
    context.setVariable("displayName", displayName);
    context.setVariable("email", to);
    context.setVariable("resetUrl", resetLink);
    context.setVariable("baseUrl", properties.getBaseUrl());
    context.setVariable(
        "preheader", "Use this secure link to reset your password (expires in 60 minutes).");
    if (required) {
      sendHtmlEmailRequired(to, subject, "mail/password-reset", context);
      return;
    }
    sendHtmlEmail(to, subject, "mail/password-reset", context);
  }

  public void sendPasswordResetConfirmation(String to, String displayName) {
    String subject = "Your BigBright ERP password has been reset";
    Context context = new Context();
    context.setVariable("displayName", displayName);
    context.setVariable("email", to);
    context.setVariable("loginUrl", properties.getBaseUrl());
    context.setVariable("preheader", "Your password has been updated successfully.");
    sendHtmlEmail(to, subject, "mail/password-reset-confirmed", context);
  }

  public void sendAdminEmailChangeVerificationRequired(
      String to,
      String displayName,
      String companyCode,
      String verificationToken,
      Instant expiresAt) {
    if (!isCredentialEmailDeliveryEnabled()) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR,
          "Credential email delivery is disabled; enable erp.mail.enabled=true and"
              + " erp.mail.send-credentials=true");
    }
    String subject = "Verify your BigBright ERP admin email change";
    Context context = new Context();
    context.setVariable("displayName", displayName);
    context.setVariable("email", to);
    context.setVariable("companyCode", companyCode);
    context.setVariable("verificationToken", verificationToken);
    context.setVariable("expiresAtUtc", formatUtcTimestamp(expiresAt));
    context.setVariable("loginUrl", properties.getBaseUrl());
    context.setVariable(
        "preheader", "Use this verification token to confirm your new admin email.");
    sendHtmlEmailRequired(to, subject, "mail/admin-email-change-verification", context);
  }

  private String formatUtcTimestamp(Instant timestamp) {
    if (timestamp == null) {
      return null;
    }
    return UTC_EMAIL_TIMESTAMP_FORMAT.format(timestamp);
  }

  public void sendTemplatedEmail(String to, String subject, String templateName, Context context) {
    sendHtmlEmail(to, subject, templateName, context);
  }

  public void sendTemplatedEmailRequired(
      String to, String subject, String templateName, Context context) {
    sendHtmlEmailRequired(to, subject, templateName, context);
  }

  public void sendUserSuspendedEmail(String to, String displayName) {
    String subject = "Your BigBright ERP account has been suspended";
    Context context = new Context();
    context.setVariable("displayName", displayName);
    context.setVariable("preheader", "Your account has been suspended.");
    sendHtmlEmail(to, subject, "mail/user-suspended", context);
  }

  public void sendUserDeletedEmail(String to, String displayName) {
    String subject = "Your BigBright ERP account has been deleted";
    Context context = new Context();
    context.setVariable("displayName", displayName);
    context.setVariable("preheader", "Your account has been deleted.");
    sendHtmlEmail(to, subject, "mail/user-deleted", context);
  }

  public void sendInvoiceEmail(
      String to,
      String dealerName,
      String invoiceNumber,
      String invoiceDate,
      String dueDate,
      String totalAmount,
      String companyName,
      byte[] pdfAttachment) {
    if (!properties.isEnabled()) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR,
          "Email delivery is disabled; enable erp.mail.enabled and SMTP settings");
    }
    if (!StringUtils.hasText(to)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Invoice recipient email is required");
    }
    String subject = "Invoice " + invoiceNumber + " from " + companyName;
    Context context = new Context();
    context.setVariable("dealerName", dealerName);
    context.setVariable("invoiceNumber", invoiceNumber);
    context.setVariable("invoiceDate", invoiceDate);
    context.setVariable("dueDate", dueDate);
    context.setVariable("totalAmount", totalAmount);
    context.setVariable("companyName", companyName);
    context.setVariable("baseUrl", properties.getBaseUrl());
    context.setVariable("subject", subject);
    context.setVariable("preheader", "Invoice " + invoiceNumber + " is ready (PDF attached).");

    MimeMessagePreparator preparator =
        mimeMessage -> {
          MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
          helper.setTo(to);
          helper.setFrom(properties.getFromAddress());
          helper.setSubject(subject);
          String html = templateEngine.process("mail/invoice-email", context);
          helper.setText(html, true);
          if (pdfAttachment != null && pdfAttachment.length > 0) {
            helper.addAttachment(
                "Invoice-" + invoiceNumber + ".pdf",
                () -> new java.io.ByteArrayInputStream(pdfAttachment),
                "application/pdf");
          }
        };
    try {
      mailSender.send(preparator);
      log.info("Sent invoice email {} to {}", invoiceNumber, to);
    } catch (MailException ex) {
      log.error("Failed to send invoice email to {}: {}", to, ex.getMessage(), ex);
      throw new ApplicationException(
              ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR,
              "Failed to deliver invoice email via SMTP",
              ex)
          .withDetail("invoiceNumber", invoiceNumber)
          .withDetail("recipient", to);
    }
  }

  /**
   * Send payroll payment sheet PDF to admin
   */
  public void sendPayrollSheetEmail(
      String to, String subject, String emailBody, String fileName, byte[] pdfAttachment) {
    if (!properties.isEnabled()) {
      log.debug("Email sending disabled. Skipping payroll email to {}", to);
      return;
    }
    if (!StringUtils.hasText(to)) {
      log.warn("Attempted to send payroll email with empty recipient");
      return;
    }
    MimeMessagePreparator preparator =
        mimeMessage -> {
          MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
          helper.setTo(to);
          helper.setFrom(properties.getFromAddress());
          helper.setSubject(subject);
          helper.setText(emailBody);
          if (pdfAttachment != null && pdfAttachment.length > 0) {
            helper.addAttachment(
                fileName, () -> new java.io.ByteArrayInputStream(pdfAttachment), "application/pdf");
          }
        };
    try {
      mailSender.send(preparator);
      log.info("Sent payroll sheet email to {}", to);
    } catch (MailException ex) {
      log.error("Failed to send payroll email to {}: {}", to, ex.getMessage(), ex);
    }
  }

  private void sendHtmlEmail(String to, String subject, String templateName, Context context) {
    if (!properties.isEnabled()) {
      log.debug("Email sending disabled. Skipping HTML email to {}", to);
      return;
    }
    if (!StringUtils.hasText(to)) {
      log.warn("Attempted to send email with empty recipient");
      return;
    }
    MimeMessagePreparator preparator =
        mimeMessage -> {
          MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
          helper.setTo(to);
          helper.setFrom(properties.getFromAddress());
          helper.setSubject(subject);
          applyStandardTemplateVariables(context, subject);
          String html = templateEngine.process(templateName, context);
          helper.setText(html, true);
        };
    try {
      mailSender.send(preparator);
      log.info("Sent HTML email to {}", to);
    } catch (MailException ex) {
      log.error("Failed to send HTML email to {}: {}", to, ex.getMessage(), ex);
    }
  }

  private void sendHtmlEmailRequired(
      String to, String subject, String templateName, Context context) {
    if (!properties.isEnabled()) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR,
          "Email delivery is disabled; enable erp.mail.enabled and SMTP settings");
    }
    if (!StringUtils.hasText(to)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Email recipient is required");
    }
    MimeMessagePreparator preparator =
        mimeMessage -> {
          MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
          helper.setTo(to);
          helper.setFrom(properties.getFromAddress());
          helper.setSubject(subject);
          applyStandardTemplateVariables(context, subject);
          String html = templateEngine.process(templateName, context);
          helper.setText(html, true);
        };
    try {
      mailSender.send(preparator);
      log.info("Sent HTML email to {}", to);
    } catch (MailException ex) {
      log.error("Failed to send HTML email to {}: {}", to, ex.getMessage(), ex);
      throw new ApplicationException(
              ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR, "Failed to dispatch email via SMTP", ex)
          .withDetail("recipient", to);
    }
  }

  private void applyStandardTemplateVariables(Context context, String subject) {
    if (context == null) {
      return;
    }
    if (context.getVariable("subject") == null) {
      context.setVariable("subject", subject);
    }
    if (context.getVariable("preheader") == null) {
      context.setVariable("preheader", subject);
    }
    if (context.getVariable("baseUrl") == null) {
      context.setVariable("baseUrl", properties.getBaseUrl());
    }
  }
}
