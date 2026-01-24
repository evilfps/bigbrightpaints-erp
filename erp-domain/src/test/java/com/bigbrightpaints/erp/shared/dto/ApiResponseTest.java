package com.bigbrightpaints.erp.shared.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void success_withMessageAndData() {
        ApiResponse<String> response = ApiResponse.success("ok", "payload");
        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void success_withoutMessage() {
        ApiResponse<String> response = ApiResponse.success("payload");
        assertThat(response.success()).isTrue();
        assertThat(response.message()).isNull();
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void failure_withMessage() {
        ApiResponse<String> response = ApiResponse.failure("bad");
        assertThat(response.success()).isFalse();
        assertThat(response.message()).isEqualTo("bad");
        assertThat(response.data()).isNull();
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void failure_withMessageAndData() {
        ApiResponse<String> response = ApiResponse.failure("bad", "payload");
        assertThat(response.success()).isFalse();
        assertThat(response.message()).isEqualTo("bad");
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.timestamp()).isNotNull();
    }
}
