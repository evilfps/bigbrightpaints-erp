package com.bigbrightpaints.erp.shared.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void of_calculatesTotalPagesExactDivision() {
        PageResponse<Integer> response = PageResponse.of(List.of(1, 2), 20, 0, 10);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(10);
    }

    @Test
    void of_calculatesTotalPagesRoundingUp() {
        PageResponse<Integer> response = PageResponse.of(List.of(1), 21, 1, 10);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(10);
    }

    @Test
    void of_zeroElements_hasZeroPages() {
        PageResponse<Integer> response = PageResponse.of(List.of(), 0, 0, 10);
        assertThat(response.totalPages()).isEqualTo(0);
        assertThat(response.content()).isEmpty();
    }
}
