package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void validate_nullPassword_returnsRequiredViolation() {
        List<String> violations = policy.validate(null);
        assertThat(violations).contains("Password is required");
    }

    @Test
    void validate_shortPassword_returnsLengthViolation() {
        List<String> violations = policy.validate("Ab1!");
        assertThat(violations).contains("Must be at least 10 characters long");
    }

    @Test
    void validate_missingLowercase_returnsViolation() {
        List<String> violations = policy.validate("ABCDEF12!@");
        assertThat(violations).contains("Must include a lowercase letter");
    }

    @Test
    void validate_missingUppercase_returnsViolation() {
        List<String> violations = policy.validate("abcdef12!@");
        assertThat(violations).contains("Must include an uppercase letter");
    }

    @Test
    void validate_missingDigit_returnsViolation() {
        List<String> violations = policy.validate("Abcdefghij!");
        assertThat(violations).contains("Must include a digit");
    }

    @Test
    void validate_missingSymbol_returnsViolation() {
        List<String> violations = policy.validate("Abcdefghij1");
        assertThat(violations).contains("Must include a special character");
    }

    @Test
    void validate_whitespace_returnsViolation() {
        List<String> violations = policy.validate("Abcdef1!  ");
        assertThat(violations).contains("Must not contain whitespace");
    }

    @Test
    void validate_strongPassword_returnsNoViolations() {
        List<String> violations = policy.validate("Abcdef1!23");
        assertThat(violations).isEmpty();
    }
}
