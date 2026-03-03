package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SizeVariantRepository extends JpaRepository<SizeVariant, Long> {

    List<SizeVariant> findByCompanyAndProductOrderBySizeLabelAsc(Company company, ProductionProduct product);

    Optional<SizeVariant> findByCompanyAndProductAndSizeLabelIgnoreCase(Company company,
                                                                        ProductionProduct product,
                                                                        String sizeLabel);
}
