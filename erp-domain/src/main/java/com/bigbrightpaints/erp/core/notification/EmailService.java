package com.bigbrightpaints.erp.core.notification;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final EmailProperties properties;

    public EmailService(JavaMailSender mailSender, EmailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendSimpleEmail(String to, String subject, String body) {
        if (!properties.isEnabled()) {
            log.debug("Email sending disabled. Skipping email to {}", to);
            return;
        }
        if (!StringUtils.hasText(to)) {
            log.warn("Attempted to send email with empty recipient");
            return;
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
        }
    }

    public void sendUserCredentialsEmail(String to, String displayName, String password) {
        if (!properties.isSendCredentials()) {
            log.debug("Credential email sending disabled. Skipping for {}", to);
            return;
        }
        String subject = "Your BigBright ERP account credentials";
        StringBuilder body = new StringBuilder();
        if (StringUtils.hasText(displayName)) {
            body.append("Hi ").append(displayName).append(",\n\n");
        } else {
            body.append("Hi,\n\n");
        }
        body.append("Your account for BigBright ERP has been created.\n\n")
            .append("Login email: ").append(to).append("\n")
            .append("Temporary password: ").append(password).append("\n\n")
            .append("You can sign in here: ").append(properties.getBaseUrl()).append("\n\n")
            .append("For security, please change your password after first login.\n\n")
            .append("Regards,\n")
            .append("BigBright ERP Team");

        sendSimpleEmail(to, subject, body.toString());
    }

    public void sendPasswordResetEmail(String to, String displayName, String resetToken) {
        if (!properties.isSendPasswordReset()) {
            log.debug("Password reset email sending disabled. Skipping for {}", to);
            return;
        }
        String resetLink = properties.getBaseUrl() + "/reset-password?token=" + resetToken;
        String subject = "Reset your BigBright ERP password";
        StringBuilder body = new StringBuilder();
        if (StringUtils.hasText(displayName)) {
            body.append("Hi ").append(displayName).append(",\n\n");
        } else {
            body.append("Hi,\n\n");
        }
        body.append("We received a request to reset the password for your BigBright ERP account.\n\n")
            .append("You can reset your password by clicking the link below:\n")
            .append(resetLink).append("\n\n")
            .append("If you did not request this, you can safely ignore this email.\n\n")
            .append("Regards,\n")
            .append("BigBright ERP Team");

        sendSimpleEmail(to, subject, body.toString());
    }
}

