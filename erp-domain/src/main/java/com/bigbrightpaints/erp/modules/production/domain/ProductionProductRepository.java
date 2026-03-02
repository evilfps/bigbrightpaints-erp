package com.bigbrightpaints.erp.modules.production.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductionProductRepository extends JpaRepository<ProductionProduct, Long>, JpaSpecificationExecutor<ProductionProduct> {
    Optional<ProductionProduct> findByCompanyAndSkuCode(Company company, String skuCode);
    Optional<ProductionProduct> findByCompanyAndId(Company company, Long id);
    Optional<ProductionProduct> findByBrandAndProductNameIgnoreCase(ProductionBrand brand, String productName);
    Optional<ProductionProduct> findTopByCompanyAndSkuCodeStartingWithOrderBySkuCodeDesc(Company company, String prefix);
    @EntityGraph(attributePaths = "brand")
    List<ProductionProduct> findByCompanyOrderByProductNameAsc(Company company);

    @EntityGraph(attributePaths = "brand")
    List<ProductionProduct> findByBrandOrderByProductNameAsc(ProductionBrand brand);

    List<ProductionProduct> findByCompanyAndSkuCodeIn(Company company, Collection<String> skuCodes);

    @Query("select p from ProductionProduct p where p.brand in :brands and lower(p.productName) in :names")
    List<ProductionProduct> findByBrandInAndProductNameInIgnoreCase(@Param("brands") Collection<ProductionBrand> brands,
                                                                    @Param("names") Collection<String> names);
}
