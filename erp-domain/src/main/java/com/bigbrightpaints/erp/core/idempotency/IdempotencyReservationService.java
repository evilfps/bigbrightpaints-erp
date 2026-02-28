package com.bigbrightpaints.erp.core.idempotency;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class IdempotencyReservationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    public String normalizeKey(String raw) {
        return IdempotencyUtils.normalizeKey(raw);
    }

    public String requireKey(String raw, String label) {
        String key = normalizeKey(raw);
        if (!StringUtils.hasText(key)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key is required for " + label);
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Idempotency key exceeds 128 characters");
        }
        return key;
    }

    public boolean isDataIntegrityViolation(Throwable error) {
        return IdempotencyUtils.isDataIntegrityViolation(error);
    }

    public ApplicationException payloadMismatch(String idempotencyKey) {
        return new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                "Idempotency key already used with different payload")
                .withDetail("idempotencyKey", idempotencyKey);
    }

    public <T> void assertAndRepairSignature(T record,
                                             String idempotencyKey,
                                             String expectedSignature,
                                             Function<T, String> signatureExtractor,
                                             BiConsumer<T, String> signatureSetter,
                                             Consumer<T> signatureSaver) {
        assertAndRepairSignature(
                record,
                idempotencyKey,
                expectedSignature,
                signatureExtractor,
                signatureSetter,
                signatureSaver,
                () -> payloadMismatch(idempotencyKey)
        );
    }

    public <T> void assertAndRepairSignature(T record,
                                             String idempotencyKey,
                                             String expectedSignature,
                                             Function<T, String> signatureExtractor,
                                             BiConsumer<T, String> signatureSetter,
                                             Consumer<T> signatureSaver,
                                             Supplier<ApplicationException> mismatchSupplier) {
        String storedSignature = signatureExtractor.apply(record);
        if (StringUtils.hasText(storedSignature)) {
            if (!Objects.equals(storedSignature, expectedSignature)) {
                throw mismatchSupplier.get();
            }
            return;
        }
        signatureSetter.accept(record, expectedSignature);
        signatureSaver.accept(record);
    }

    public <T> Reservation<T> reserve(Supplier<Optional<T>> existingLookup,
                                      Supplier<T> reservationCreator) {
        Optional<T> existing = existingLookup.get();
        if (existing.isPresent()) {
            return new Reservation<>(false, existing.get());
        }
        try {
            return new Reservation<>(true, reservationCreator.get());
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            Optional<T> concurrent = existingLookup.get();
            if (concurrent.isPresent()) {
                return new Reservation<>(false, concurrent.get());
            }
            throw ex;
        }
    }

    public record Reservation<T>(boolean leader, T record) {
    }
}
