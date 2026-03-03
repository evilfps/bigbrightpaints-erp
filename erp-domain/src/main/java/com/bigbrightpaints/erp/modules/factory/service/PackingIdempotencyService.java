package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRequestRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRequestRecordRepository;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class PackingIdempotencyService {

    private final PackingRequestRecordRepository packingRequestRecordRepository;
    private final ProductionLogService productionLogService;

    public PackingIdempotencyService(PackingRequestRecordRepository packingRequestRecordRepository,
                                     ProductionLogService productionLogService) {
        this.packingRequestRecordRepository = packingRequestRecordRepository;
        this.productionLogService = productionLogService;
    }

    public String packingRequestHash(PackingRequest request, LocalDate packedDate) {
        StringBuilder payload = new StringBuilder();
        payload.append("log=").append(request.productionLogId())
                .append("|date=").append(packedDate)
                .append("|by=").append(clean(request.packedBy()));
        if (request.lines() != null) {
            int idx = 0;
            for (var line : request.lines()) {
                idx++;
                payload.append("|line").append(idx).append(':')
                        .append(clean(line.packagingSize()))
                        .append(':').append(line.childFinishedGoodId())
                        .append(':').append(line.childBatchCount())
                        .append(':').append(decimalToken(line.quantityLiters()))
                        .append(':').append(line.piecesCount())
                        .append(':').append(line.boxesCount())
                        .append(':').append(line.piecesPerBox());
            }
        }
        return IdempotencyUtils.sha256Hex(payload.toString());
    }

    public IdempotencyReservation reserveIdempotencyRecord(Company company,
                                                           Long productionLogId,
                                                           String idempotencyKey,
                                                           String idempotencyHash) {
        Optional<PackingRequestRecord> existing = packingRequestRecordRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (existing.isPresent()) {
            return resolveExistingIdempotency(existing.get(), productionLogId, idempotencyHash);
        }

        PackingRequestRecord record = new PackingRequestRecord();
        record.setCompany(company);
        record.setIdempotencyKey(idempotencyKey);
        record.setIdempotencyHash(idempotencyHash);
        record.setProductionLogId(productionLogId);
        try {
            PackingRequestRecord saved = packingRequestRecordRepository.saveAndFlush(record);
            return new IdempotencyReservation(saved, null);
        } catch (DataIntegrityViolationException ex) {
            PackingRequestRecord collided = packingRequestRecordRepository
                    .findByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow(() -> ex);
            return resolveExistingIdempotency(collided, productionLogId, idempotencyHash);
        }
    }

    public void markCompleted(IdempotencyReservation reservation, Long packingRecordId) {
        if (reservation == null || reservation.record() == null) {
            return;
        }
        reservation.record().setPackingRecordId(packingRecordId);
        packingRequestRecordRepository.save(reservation.record());
    }

    private IdempotencyReservation resolveExistingIdempotency(PackingRequestRecord record,
                                                              Long productionLogId,
                                                              String idempotencyHash) {
        if (!productionLogId.equals(record.getProductionLogId())) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used for a different production log")
                    .withDetail("idempotencyKey", record.getIdempotencyKey())
                    .withDetail("existingProductionLogId", record.getProductionLogId())
                    .withDetail("requestedProductionLogId", productionLogId);
        }

        if (StringUtils.hasText(record.getIdempotencyHash())
                && !record.getIdempotencyHash().equals(idempotencyHash)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency payload mismatch for packing request")
                    .withDetail("idempotencyKey", record.getIdempotencyKey())
                    .withDetail("productionLogId", productionLogId);
        }

        ProductionLogDetailDto replay = productionLogService.getLog(record.getProductionLogId());
        return new IdempotencyReservation(record, replay);
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String decimalToken(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    public record IdempotencyReservation(PackingRequestRecord record,
                                         ProductionLogDetailDto replayResult) {
    }
}
