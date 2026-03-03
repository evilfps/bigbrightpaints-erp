package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Component
public class PackingLineResolver {

    private final SizeVariantRepository sizeVariantRepository;

    public PackingLineResolver(SizeVariantRepository sizeVariantRepository) {
        this.sizeVariantRepository = sizeVariantRepository;
    }

    public String normalizePackagingSize(String size, int lineNumber) {
        if (!StringUtils.hasText(size)) {
            throw ValidationUtils.invalidInput("Packaging size is required for line " + lineNumber);
        }
        return size.trim().toUpperCase();
    }

    public SizeVariant resolveSizeVariant(Company company, ProductionLog log, String packagingSize) {
        ProductionProduct product = log.getProduct();
        SizeVariant existing = sizeVariantRepository
                .findByCompanyAndProductAndSizeLabelIgnoreCase(company, product, packagingSize)
                .orElse(null);
        if (existing != null) {
            if (!existing.isActive()) {
                existing.setActive(true);
                return sizeVariantRepository.save(existing);
            }
            return existing;
        }
        return createDefaultSizeVariant(company, product, packagingSize);
    }

    public Integer resolvePiecesPerBox(PackingLineRequest line, SizeVariant sizeVariant) {
        if (line.piecesPerBox() != null && line.piecesPerBox() > 0) {
            return line.piecesPerBox();
        }
        if (sizeVariant != null && sizeVariant.getCartonQuantity() != null && sizeVariant.getCartonQuantity() > 0) {
            return sizeVariant.getCartonQuantity();
        }
        return null;
    }

    public int resolvePiecesCountForLine(PackingLineRequest line,
                                         Integer resolvedPiecesPerBox,
                                         int lineNumber) {
        if (line.piecesCount() != null && line.piecesCount() > 0) {
            return line.piecesCount();
        }
        if (line.boxesCount() != null && line.boxesCount() > 0
                && resolvedPiecesPerBox != null && resolvedPiecesPerBox > 0) {
            return line.boxesCount() * resolvedPiecesPerBox;
        }
        throw ValidationUtils.invalidInput("Pieces count or boxes × pieces per box required for line " + lineNumber);
    }

    public BigDecimal resolveQuantity(PackingLineRequest line,
                                      SizeVariant sizeVariant,
                                      String normalizedSize,
                                      int piecesCount,
                                      int lineNumber) {
        if (line.quantityLiters() != null && line.quantityLiters().compareTo(BigDecimal.ZERO) > 0) {
            return line.quantityLiters();
        }
        BigDecimal litersPerUnit = resolveLitersPerUnit(sizeVariant, normalizedSize, lineNumber);
        return MoneyUtils.safeMultiply(litersPerUnit, BigDecimal.valueOf(piecesCount));
    }

    public int resolveChildBatchCount(PackingLineRequest line, int piecesCount) {
        if (line.childBatchCount() != null && line.childBatchCount() > 0) {
            return line.childBatchCount();
        }
        return piecesCount;
    }

    private BigDecimal resolveLitersPerUnit(SizeVariant sizeVariant,
                                            String normalizedSize,
                                            int lineNumber) {
        if (sizeVariant != null
                && sizeVariant.getLitersPerUnit() != null
                && sizeVariant.getLitersPerUnit().compareTo(BigDecimal.ZERO) > 0) {
            return sizeVariant.getLitersPerUnit();
        }
        BigDecimal parsed = PackagingSizeParser.parseSizeInLitersAllowBareNumber(normalizedSize);
        if (parsed == null || parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw ValidationUtils.invalidInput(
                    "Invalid packaging size '" + normalizedSize + "' on line " + lineNumber);
        }
        return parsed;
    }

    private SizeVariant createDefaultSizeVariant(Company company,
                                                 ProductionProduct product,
                                                 String packagingSize) {
        SizeVariant variant = new SizeVariant();
        variant.setCompany(company);
        variant.setProduct(product);
        variant.setSizeLabel(packagingSize);
        variant.setCartonQuantity(1);
        variant.setLitersPerUnit(resolveLitersFromSize(packagingSize));
        variant.setActive(true);
        return sizeVariantRepository.save(variant);
    }

    private BigDecimal resolveLitersFromSize(String packagingSize) {
        BigDecimal parsed = PackagingSizeParser.parseSizeInLitersAllowBareNumber(packagingSize);
        if (parsed == null || parsed.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return parsed;
    }
}
