package com.bigbrightpaints.erp.shared.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorResponseTest {

    @Test
    void of_populatesFields() {
        ErrorResponse response = ErrorResponse.of(400, "Bad Request", "Invalid", "/api/test", Map.of("field", "value"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.error()).isEqualTo("Bad Request");
        assertThat(response.message()).isEqualTo("Invalid");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.details()).containsEntry("field", "value");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void of_allowsNullDetails() {
        ErrorResponse response = ErrorResponse.of(404, "Not Found", "Missing", "/api/missing", null);
        assertThat(response.details()).isNull();
        assertThat(response.timestamp()).isNotNull();
    }
}
