package com.bigbrightpaints.erp.modules.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class CreditLimitRequestDtoTest {

    @Test
    void accessorsExposeAllFields() {
        UUID publicId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Instant createdAt = Instant.parse("2026-03-23T10:15:30Z");
        CreditLimitRequestDto dto = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer One",
                new BigDecimal("1500.50"),
                "PENDING",
                "Need durable increase",
                createdAt
        );

        assertThat(dto.id()).isEqualTo(901L);
        assertThat(dto.getId()).isEqualTo(901L);
        assertThat(dto.publicId()).isEqualTo(publicId);
        assertThat(dto.getPublicId()).isEqualTo(publicId);
        assertThat(dto.dealerName()).isEqualTo("Dealer One");
        assertThat(dto.getDealerName()).isEqualTo("Dealer One");
        assertThat(dto.amountRequested()).isEqualByComparingTo("1500.50");
        assertThat(dto.getAmountRequested()).isEqualByComparingTo("1500.50");
        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(dto.getStatus()).isEqualTo("PENDING");
        assertThat(dto.reason()).isEqualTo("Need durable increase");
        assertThat(dto.getReason()).isEqualTo("Need durable increase");
        assertThat(dto.createdAt()).isEqualTo(createdAt);
        assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
        assertThat(dto.toString()).contains("id=901", "Dealer One", "PENDING");
    }

    @Test
    void equalsAndHashCodeFollowValueSemantics() {
        UUID publicId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Instant createdAt = Instant.parse("2026-03-23T10:15:30Z");
        CreditLimitRequestDto dto = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer One",
                new BigDecimal("1500.50"),
                "PENDING",
                "Need durable increase",
                createdAt
        );
        CreditLimitRequestDto same = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer One",
                new BigDecimal("1500.50"),
                "PENDING",
                "Need durable increase",
                createdAt
        );
        CreditLimitRequestDto different = new CreditLimitRequestDto(
                902L,
                publicId,
                "Dealer Two",
                new BigDecimal("800.00"),
                "APPROVED",
                "Different",
                createdAt.plusSeconds(60)
        );
        CreditLimitRequestDto differentPublicId = new CreditLimitRequestDto(
                901L,
                UUID.fromString("223e4567-e89b-12d3-a456-426614174000"),
                "Dealer One",
                new BigDecimal("1500.50"),
                "PENDING",
                "Need durable increase",
                createdAt
        );
        CreditLimitRequestDto differentDealerName = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer Two",
                new BigDecimal("1500.50"),
                "PENDING",
                "Need durable increase",
                createdAt
        );
        CreditLimitRequestDto differentAmount = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer One",
                new BigDecimal("900.00"),
                "PENDING",
                "Need durable increase",
                createdAt
        );
        CreditLimitRequestDto differentStatus = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer One",
                new BigDecimal("1500.50"),
                "APPROVED",
                "Need durable increase",
                createdAt
        );
        CreditLimitRequestDto differentReason = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer One",
                new BigDecimal("1500.50"),
                "PENDING",
                "Different",
                createdAt
        );
        CreditLimitRequestDto differentCreatedAt = new CreditLimitRequestDto(
                901L,
                publicId,
                "Dealer One",
                new BigDecimal("1500.50"),
                "PENDING",
                "Need durable increase",
                createdAt.plusSeconds(60)
        );

        assertThat(dto).isEqualTo(dto);
        assertThat(dto).isEqualTo(same);
        assertThat(dto.hashCode()).isEqualTo(same.hashCode());
        assertThat(dto).isNotEqualTo(different);
        assertThat(dto).isNotEqualTo(differentPublicId);
        assertThat(dto).isNotEqualTo(differentDealerName);
        assertThat(dto).isNotEqualTo(differentAmount);
        assertThat(dto).isNotEqualTo(differentStatus);
        assertThat(dto).isNotEqualTo(differentReason);
        assertThat(dto).isNotEqualTo(differentCreatedAt);
        assertThat(dto).isNotEqualTo("other");
    }
}
