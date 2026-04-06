package com.bigbrightpaints.erp.core.security;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;

/**
 * Service for monitoring security events and detecting suspicious activities.
 * Implements various security monitoring patterns including:
 * - Failed login attempt tracking
 * - Brute force detection
 * - Suspicious activity patterns
 * - Rate limiting violations
 * - Anomaly detection
 */
@Service
public class SecurityMonitoringService {

  private static final Logger logger = LoggerFactory.getLogger(SecurityMonitoringService.class);

  @Autowired private AuditService auditService;

  @Autowired private AuditLogRepository auditLogRepository;

  @Autowired private TokenBlacklistService tokenBlacklistService;

  @Autowired private EmailService emailService;

  // Configuration
  @Value("${security.monitoring.max-failed-logins:5}")
  private int maxFailedLogins;

  @Value("${security.monitoring.failed-login-window-minutes:15}")
  private int failedLoginWindowMinutes;

  @Value("${security.monitoring.max-requests-per-minute:100}")
  private int maxRequestsPerMinute;

  @Value("${security.monitoring.suspicious-activity-threshold:10}")
  private int suspiciousActivityThreshold;

  @Value("${security.monitoring.notification-email:}")
  private String securityNotificationEmail;

  @Value("${security.monitoring.suspicious-activity-alert-window-minutes:60}")
  private int suspiciousActivityAlertWindowMinutes;

  // Tracking maps
  private final Map<String, AtomicInteger> failedLoginAttempts = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
  private final Map<String, LocalDateTime> blockedIPs = new ConcurrentHashMap<>();
  private final Map<String, LocalDateTime> blockedUsers = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> suspiciousActivityScores = new ConcurrentHashMap<>();
  private final Map<String, LocalDateTime> suspiciousActivityNotificationTimes =
      new ConcurrentHashMap<>();

  /**
   * Records a failed login attempt and checks for brute force attacks.
   */
  public void recordFailedLogin(String username, String ipAddress) {
    // Track failed attempts by username
    String userKey = "user:" + username;
    AtomicInteger userAttempts =
        failedLoginAttempts.computeIfAbsent(userKey, k -> new AtomicInteger(0));
    int userCount = userAttempts.incrementAndGet();

    // Track failed attempts by IP
    String ipKey = "ip:" + ipAddress;
    AtomicInteger ipAttempts =
        failedLoginAttempts.computeIfAbsent(ipKey, k -> new AtomicInteger(0));
    int ipCount = ipAttempts.incrementAndGet();

    // Check for brute force attack
    if (userCount >= maxFailedLogins) {
      handleBruteForceAttempt(username, ipAddress, userCount);
    }

    if (ipCount >= maxFailedLogins) {
      handleSuspiciousIP(ipAddress, ipCount);
    }

    // Increase suspicious activity score
    increaseSuspiciousScore(ipAddress, 2);

    logger.warn(
        "Failed login attempt - User: {}, IP: {}, Attempts: {}/{}",
        username,
        ipAddress,
        userCount,
        ipCount);
  }

  /**
   * Records a successful login and resets counters.
   */
  public void recordSuccessfulLogin(String username, String ipAddress) {
    // Reset failed login counters
    failedLoginAttempts.remove("user:" + username);
    failedLoginAttempts.remove("ip:" + ipAddress);

    // Check if login from new location
    checkLoginLocation(username, ipAddress);

    logger.info("Successful login - User: {}, IP: {}", username, ipAddress);
  }

  /**
   * Checks if a user or IP is currently blocked.
   */
  public boolean isBlocked(String username, String ipAddress) {
    // Check user block
    if (username != null) {
      LocalDateTime userBlockTime = blockedUsers.get(username);
      if (userBlockTime != null && userBlockTime.isAfter(LocalDateTime.now())) {
        return true;
      }
    }

    // Check IP block
    if (ipAddress != null) {
      LocalDateTime ipBlockTime = blockedIPs.get(ipAddress);
      if (ipBlockTime != null && ipBlockTime.isAfter(LocalDateTime.now())) {
        return true;
      }
    }

    return false;
  }

  /**
   * Records a request for rate limiting.
   */
  public boolean checkRateLimit(String identifier) {
    AtomicInteger count = requestCounts.computeIfAbsent(identifier, k -> new AtomicInteger(0));
    int currentCount = count.incrementAndGet();

    if (currentCount > maxRequestsPerMinute) {
      handleRateLimitViolation(identifier, currentCount);
      return false;
    }

    return true;
  }

  /**
   * Handles detected brute force attempts.
   */
  private void handleBruteForceAttempt(String username, String ipAddress, int attempts) {
    // Block the user temporarily
    LocalDateTime blockUntil = LocalDateTime.now().plusMinutes(30);
    blockedUsers.put(username, blockUntil);

    // Revoke all tokens for the user
    tokenBlacklistService.revokeAllUserTokens(username);

    // Log security alert
    Map<String, String> metadata = new HashMap<>();
    metadata.put("username", username == null ? "" : username);
    metadata.put("ipAddress", ipAddress == null ? "" : ipAddress);
    metadata.put("attempts", String.valueOf(attempts));
    metadata.put("action", "User blocked for 30 minutes");
    auditService.logSecurityAlert(
        "BRUTE_FORCE_ATTACK", "Multiple failed login attempts detected", metadata);

    logger.error(
        "SECURITY ALERT: Brute force attack detected - User: {}, IP: {}, Attempts: {}",
        username,
        ipAddress,
        attempts);

    // Send notification if an alert recipient is configured.
    sendSecurityNotification("Brute force attack", username, ipAddress);
  }

  /**
   * Handles suspicious IP addresses.
   */
  private void handleSuspiciousIP(String ipAddress, int attempts) {
    // Block the IP temporarily
    LocalDateTime blockUntil = LocalDateTime.now().plusHours(1);
    blockedIPs.put(ipAddress, blockUntil);

    // Log security alert
    Map<String, String> metadata = new HashMap<>();
    metadata.put("ipAddress", ipAddress == null ? "" : ipAddress);
    metadata.put("attempts", String.valueOf(attempts));
    metadata.put("action", "IP blocked for 1 hour");
    auditService.logSecurityAlert(
        "SUSPICIOUS_IP", "Multiple failed login attempts from IP", metadata);

    logger.error(
        "SECURITY ALERT: Suspicious IP detected - IP: {}, Attempts: {}", ipAddress, attempts);
  }

  /**
   * Handles rate limit violations.
   */
  private void handleRateLimitViolation(String identifier, int count) {
    increaseSuspiciousScore(identifier, 3);

    Map<String, String> metadata = new HashMap<>();
    metadata.put("identifier", identifier == null ? "" : identifier);
    metadata.put("requests", String.valueOf(count));
    metadata.put("limit", String.valueOf(maxRequestsPerMinute));
    auditService.logSecurityAlert("RATE_LIMIT_VIOLATION", "Rate limit exceeded", metadata);

    logger.warn("Rate limit violation - Identifier: {}, Requests: {}", identifier, count);
  }

  /**
   * Checks if login is from a new or suspicious location.
   */
  private void checkLoginLocation(String username, String ipAddress) {
    // This could be enhanced with GeoIP lookup
    // For now, just log the information
    logger.debug("Login location check - User: {}, IP: {}", username, ipAddress);
  }

  /**
   * Increases the suspicious activity score for an identifier.
   */
  private void increaseSuspiciousScore(String identifier, int points) {
    AtomicInteger score =
        suspiciousActivityScores.computeIfAbsent(identifier, k -> new AtomicInteger(0));
    int previousScore = score.getAndAdd(points);
    int newScore = previousScore + points;

    if (newScore >= suspiciousActivityThreshold) {
      handleSuspiciousActivity(identifier, newScore, previousScore < suspiciousActivityThreshold);
    }
  }

  /**
   * Handles detected suspicious activity.
   */
  private void handleSuspiciousActivity(String identifier, int score, boolean thresholdCrossed) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("identifier", identifier == null ? "" : identifier);
    metadata.put("score", String.valueOf(score));
    metadata.put("threshold", String.valueOf(suspiciousActivityThreshold));
    auditService.logSecurityAlert(
        "SUSPICIOUS_ACTIVITY", "Suspicious activity threshold exceeded", metadata);

    logger.warn(
        "SECURITY WARNING: Suspicious activity detected - Identifier: {}, Score: {}",
        identifier,
        score);

    notifySuspiciousActivityIfNeeded(identifier, thresholdCrossed);
  }

  private void notifySuspiciousActivityIfNeeded(String identifier, boolean thresholdCrossed) {
    if (!StringUtils.hasText(securityNotificationEmail)) {
      sendSecurityNotification("Suspicious activity detected", identifier, null);
      return;
    }

    if (thresholdCrossed) {
      suspiciousActivityNotificationTimes.put(identifier, LocalDateTime.now());
      sendSecurityNotification("Suspicious activity detected", identifier, null);
      return;
    }

    if (!isSuspiciousActivityNotificationDue(identifier)) {
      return;
    }

    sendSecurityNotification("Suspicious activity detected", identifier, null);
  }

  private boolean isSuspiciousActivityNotificationDue(String identifier) {
    if (suspiciousActivityAlertWindowMinutes <= 0) {
      return false;
    }

    LocalDateTime now = LocalDateTime.now();
    AtomicBoolean due = new AtomicBoolean(false);
    suspiciousActivityNotificationTimes.compute(
        identifier,
        (key, lastNotificationAt) -> {
          if (lastNotificationAt == null
              || !lastNotificationAt
                  .plusMinutes(suspiciousActivityAlertWindowMinutes)
                  .isAfter(now)) {
            due.set(true);
            return now;
          }
          return lastNotificationAt;
        });
    return due.get();
  }

  /**
   * Sends a security notification when an alert recipient is configured.
   */
  private void sendSecurityNotification(String alertType, String subject, String details) {
    if (!StringUtils.hasText(securityNotificationEmail)) {
      logger.debug(
          "Security notification skipped because no security.monitoring.notification-email is"
              + " configured for alertType={}",
          alertType);
      return;
    }

    String emailSubject = "Security alert: " + alertType;
    StringBuilder body =
        new StringBuilder("A security event requires attention.\n\nAlert type: ").append(alertType);
    if (StringUtils.hasText(subject)) {
      body.append("\nSubject: ").append(subject);
    }
    if (StringUtils.hasText(details)) {
      body.append("\nDetails: ").append(details);
    }
    body.append("\nGenerated at: ").append(LocalDateTime.now());

    try {
      emailService.sendSimpleEmail(securityNotificationEmail, emailSubject, body.toString());
    } catch (RuntimeException ex) {
      logger.warn("Failed to dispatch security notification for alertType={}", alertType, ex);
    }
  }

  /**
   * Scheduled task to clean up tracking maps.
   */
  @Scheduled(fixedDelay = 60000) // Every minute
  public void cleanupTracking() {
    LocalDateTime now = LocalDateTime.now();

    // Reset request counts
    requestCounts.clear();

    // Clean up expired blocks
    blockedUsers.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    blockedIPs.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    suspiciousActivityNotificationTimes
        .entrySet()
        .removeIf(
            entry ->
                suspiciousActivityAlertWindowMinutes <= 0
                    || !entry
                        .getValue()
                        .plusMinutes(suspiciousActivityAlertWindowMinutes)
                        .isAfter(now));

    // Clean up old failed login attempts
    // In a production implementation, you would track timestamps for each attempt
    // For simplicity, we'll just clear old entries periodically
    if (now.getMinute() % 15 == 0) {
      failedLoginAttempts.clear();
      suspiciousActivityScores.clear();
    }
  }

  /**
   * Scheduled task to analyze security trends.
   */
  @Scheduled(fixedDelay = 3600000) // Every hour
  public void analyzeSecurityTrends() {
    try {
      LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);

      // Count recent failed logins
      long failedLogins =
          auditLogRepository.findByTimestampBetween(oneHourAgo, LocalDateTime.now()).stream()
              .filter(log -> log.getEventType() == AuditEvent.LOGIN_FAILURE)
              .count();

      // Count security alerts
      long securityAlerts = auditLogRepository.findSecurityAlerts(oneHourAgo).size();

      if (failedLogins > 50 || securityAlerts > 10) {
        logger.warn(
            "SECURITY TREND ALERT: High security event activity - Failed logins: {}, Alerts: {}",
            failedLogins,
            securityAlerts);
        sendSecurityNotification(
            "Security Trend Alert",
            "High security activity detected",
            String.format("Failed logins: %d, Alerts: %d", failedLogins, securityAlerts));
      }

      logger.info(
          "Security trend analysis - Failed logins: {}, Security alerts: {}",
          failedLogins,
          securityAlerts);

    } catch (Exception e) {
      logger.error("Error analyzing security trends", e);
    }
  }

  /**
   * Gets current security metrics.
   */
  public Map<String, Object> getSecurityMetrics() {
    return Map.of(
        "blockedUsers", blockedUsers.size(),
        "blockedIPs", blockedIPs.size(),
        "activeFailedLoginTracking", failedLoginAttempts.size(),
        "suspiciousActivityTracking", suspiciousActivityScores.size(),
        "blacklistedTokens", tokenBlacklistService.getBlacklistedTokenCount());
  }
}
