package com.bigbrightpaints.erp.modules.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PackagingSlipLineRepository extends JpaRepository<PackagingSlipLine, Long> {
    List<PackagingSlipLine> findByPackagingSlipId(Long packagingSlipId);
}
