package com.bigbrightpaints.erp.core.audit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for audit log operations.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

  @Override
  @EntityGraph(attributePaths = "metadata")
  Page<AuditLog> findAll(
      org.springframework.data.jpa.domain.Specification<AuditLog> spec, Pageable pageable);

  /**
   * Find audit logs by user ID.
   */
  List<AuditLog> findByUserIdOrderByTimestampDesc(String userId);

  /**
   * Find audit logs by company ID.
   */
  List<AuditLog> findByCompanyIdOrderByTimestampDesc(Long companyId);

  @EntityGraph(attributePaths = "metadata")
  List<AuditLog> findTop50ByCompanyIdOrderByTimestampDesc(Long companyId);

  Optional<AuditLog> findTop1ByCompanyIdOrderByTimestampDesc(Long companyId);

  /**
   * Find audit logs by event type.
   */
  @EntityGraph(attributePaths = "metadata")
  List<AuditLog> findByEventTypeOrderByTimestampDesc(AuditEvent eventType);

  /**
   * Find latest event record for a username.
   */
  Optional<AuditLog> findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
      AuditEvent eventType, Long companyId, String username);

  /**
   * Projection for batched username login lookups.
   */
  interface UsernameLastLoginProjection {
    String getUsernameKey();

    LocalDateTime getLastLoginAt();
  }

  /**
   * Resolve latest login timestamp by normalized username for a batch of users.
   */
  @Query(
      "SELECT LOWER(al.username) AS usernameKey, MAX(al.timestamp) AS lastLoginAt "
          + "FROM AuditLog al "
          + "WHERE al.eventType = :eventType "
          + "AND al.companyId = :companyId "
          + "AND al.username IS NOT NULL "
          + "AND LOWER(al.username) IN :usernames "
          + "GROUP BY LOWER(al.username)")
  List<UsernameLastLoginProjection> findLatestTimestampByEventTypeAndCompanyIdAndUsernameIn(
      @Param("eventType") AuditEvent eventType,
      @Param("companyId") Long companyId,
      @Param("usernames") java.util.Set<String> usernames);

  /**
   * Find audit logs by event type and eagerly load metadata for assertions/reporting.
   */
  @Query(
      "SELECT DISTINCT al FROM AuditLog al LEFT JOIN FETCH al.metadata WHERE al.eventType ="
          + " :eventType ORDER BY al.timestamp DESC")
  List<AuditLog> findByEventTypeWithMetadataOrderByTimestampDesc(
      @Param("eventType") AuditEvent eventType);

  /**
   * Find audit logs within a time range.
   */
  @Query(
      "SELECT al FROM AuditLog al WHERE al.timestamp BETWEEN :startTime AND :endTime ORDER BY"
          + " al.timestamp DESC")
  List<AuditLog> findByTimestampBetween(
      @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

  /**
   * Find failed authentication attempts for a user.
   */
  @Query(
      "SELECT al FROM AuditLog al WHERE al.username = :username "
          + "AND al.eventType = 'LOGIN_FAILURE' "
          + "AND al.timestamp > :since "
          + "ORDER BY al.timestamp DESC")
  List<AuditLog> findFailedLoginAttempts(
      @Param("username") String username, @Param("since") LocalDateTime since);

  /**
   * Count failed authentication attempts from an IP address.
   */
  @Query(
      "SELECT COUNT(al) FROM AuditLog al WHERE al.ipAddress = :ipAddress "
          + "AND al.eventType = 'LOGIN_FAILURE' "
          + "AND al.timestamp > :since")
  long countFailedLoginAttemptsFromIp(
      @Param("ipAddress") String ipAddress, @Param("since") LocalDateTime since);

  /**
   * Find security alerts.
   */
  @Query(
      "SELECT al FROM AuditLog al WHERE al.eventType = 'SECURITY_ALERT' "
          + "AND al.timestamp > :since "
          + "ORDER BY al.timestamp DESC")
  List<AuditLog> findSecurityAlerts(@Param("since") LocalDateTime since);

  /**
   * Find audit logs by trace ID.
   */
  List<AuditLog> findByTraceIdOrderByTimestamp(String traceId);

  /**
   * Count tenant-scoped API activity rows with request context.
   */
  @Query(
      "SELECT COUNT(al) FROM AuditLog al "
          + "WHERE al.companyId = :companyId "
          + "AND al.requestMethod IS NOT NULL AND al.requestMethod <> '' "
          + "AND al.requestPath IS NOT NULL AND al.requestPath <> ''")
  long countApiActivityByCompanyId(@Param("companyId") Long companyId);

  /**
   * Count tenant-scoped API activity rows marked as failures.
   */
  @Query(
      "SELECT COUNT(al) FROM AuditLog al "
          + "WHERE al.companyId = :companyId "
          + "AND al.requestMethod IS NOT NULL AND al.requestMethod <> '' "
          + "AND al.requestPath IS NOT NULL AND al.requestPath <> '' "
          + "AND al.status = 'FAILURE'")
  long countApiFailureActivityByCompanyId(@Param("companyId") Long companyId);

  /**
   * Count distinct tenant sessions observed in request-context audit rows.
   */
  @Query(
      "SELECT COUNT(DISTINCT al.sessionId) FROM AuditLog al "
          + "WHERE al.companyId = :companyId "
          + "AND al.sessionId IS NOT NULL AND al.sessionId <> '' "
          + "AND al.requestMethod IS NOT NULL AND al.requestMethod <> '' "
          + "AND al.requestPath IS NOT NULL AND al.requestPath <> ''")
  long countDistinctSessionActivityByCompanyId(@Param("companyId") Long companyId);

  /**
   * Estimate tenant audit storage footprint in bytes (PostgreSQL row-size aggregate).
   */
  @Query(
      value =
          "SELECT COALESCE(SUM(pg_column_size(al)), 0) FROM audit_logs al WHERE al.company_id ="
              + " :companyId",
      nativeQuery = true)
  long estimateAuditStorageBytesByCompanyId(@Param("companyId") Long companyId);

  /**
   * Delete old audit logs.
   */
  void deleteByTimestampBefore(LocalDateTime cutoff);
}
