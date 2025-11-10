package com.bigbrightpaints.erp.orchestrator.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobDefinitionRepository extends JpaRepository<ScheduledJobDefinition, String> {

    Optional<ScheduledJobDefinition> findByJobIdAndActiveTrue(String jobId);
}
