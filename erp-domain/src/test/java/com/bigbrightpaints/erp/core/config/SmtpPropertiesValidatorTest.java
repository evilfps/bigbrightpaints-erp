package com.bigbrightpaints.erp.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpPropertiesValidatorTest {

    @Test
    void validateSmtp_rejectsMissingPassword() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "mailEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpAuthEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpHost", "smtp.example.com");
        ReflectionTestUtils.setField(validator, "smtpPassword", "");
        ReflectionTestUtils.setField(validator, "smtpUser", "mailer");

        assertThatThrownBy(validator::validateSmtp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP password is missing");
    }

    @Test
    void validateSmtp_rejectsDefaultPlaceholderPassword() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "mailEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpAuthEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpHost", "smtp.example.com");
        ReflectionTestUtils.setField(validator, "smtpPassword", "changeme");
        ReflectionTestUtils.setField(validator, "smtpUser", "mailer");

        assertThatThrownBy(validator::validateSmtp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changeme");
    }

    @Test
    void validateSmtp_rejectsMissingUsername() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "mailEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpAuthEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpHost", "smtp.example.com");
        ReflectionTestUtils.setField(validator, "smtpPassword", "prod-secret-value");
        ReflectionTestUtils.setField(validator, "smtpUser", "");

        assertThatThrownBy(validator::validateSmtp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP username is missing");
    }

    @Test
    void validateSmtp_acceptsConfiguredCredentials() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "mailEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpAuthEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpHost", "smtp.example.com");
        ReflectionTestUtils.setField(validator, "smtpPassword", "prod-secret-value");
        ReflectionTestUtils.setField(validator, "smtpUser", "mailer");

        assertThatCode(validator::validateSmtp).doesNotThrowAnyException();
    }

    @Test
    void validateSmtp_skipsValidationWhenMailDisabled() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "mailEnabled", false);
        ReflectionTestUtils.setField(validator, "smtpAuthEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpHost", "");
        ReflectionTestUtils.setField(validator, "smtpPassword", "");
        ReflectionTestUtils.setField(validator, "smtpUser", "");

        assertThatCode(validator::validateSmtp).doesNotThrowAnyException();
    }

    @Test
    void validateSmtp_skipsCredentialValidationWhenAuthDisabled() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "mailEnabled", true);
        ReflectionTestUtils.setField(validator, "smtpAuthEnabled", false);
        ReflectionTestUtils.setField(validator, "smtpHost", "smtp.example.com");
        ReflectionTestUtils.setField(validator, "smtpPassword", "");
        ReflectionTestUtils.setField(validator, "smtpUser", "");

        assertThatCode(validator::validateSmtp).doesNotThrowAnyException();
    }
}
