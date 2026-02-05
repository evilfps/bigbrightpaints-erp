package com.bigbrightpaints.erp.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpPropertiesValidatorTest {

    @Test
    void validateSmtp_rejectsMissingPassword() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "smtpPassword", "");
        ReflectionTestUtils.setField(validator, "smtpUser", "mailer");

        assertThatThrownBy(validator::validateSmtp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP password is missing");
    }

    @Test
    void validateSmtp_rejectsDefaultPlaceholderPassword() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "smtpPassword", "changeme");
        ReflectionTestUtils.setField(validator, "smtpUser", "mailer");

        assertThatThrownBy(validator::validateSmtp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changeme");
    }

    @Test
    void validateSmtp_acceptsConfiguredPassword() {
        SmtpPropertiesValidator validator = new SmtpPropertiesValidator();
        ReflectionTestUtils.setField(validator, "smtpPassword", "prod-secret-value");
        ReflectionTestUtils.setField(validator, "smtpUser", "mailer");

        assertThatCode(validator::validateSmtp).doesNotThrowAnyException();
    }
}
