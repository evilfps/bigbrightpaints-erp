package com.bigbrightpaints.erp.orchestrator.scheduler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.orchestrator.repository.ScheduledJobDefinition;
import com.bigbrightpaints.erp.orchestrator.repository.ScheduledJobDefinitionRepository;

@Service
@ConditionalOnProperty(
    value = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SchedulerService {

  private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

  private final TaskScheduler taskScheduler;
  private final ScheduledJobDefinitionRepository repository;
  private final Map<String, ScheduledFuture<?>> scheduledHandles = new ConcurrentHashMap<>();

  public SchedulerService(
      TaskScheduler taskScheduler, ScheduledJobDefinitionRepository repository) {
    this.taskScheduler = taskScheduler;
    this.repository = repository;
  }

  @Transactional
  public void registerJob(
      String jobId, String cronExpression, Runnable jobLogic, String description, String owner) {
    ScheduledJobDefinition definition =
        repository
            .findById(jobId)
            .orElseGet(() -> new ScheduledJobDefinition(jobId, cronExpression, description, owner));
    definition.setCronExpression(cronExpression);
    definition.setDescription(description);
    definition.setOwner(owner);
    definition.setActive(true);
    repository.save(definition);
    cancelScheduled(jobId);
    scheduledHandles.put(jobId, schedule(jobId, cronExpression, jobLogic));
    log.info("Registered job {} with cron {}", jobId, cronExpression);
  }

  @Transactional
  public void pauseJob(String jobId) {
    repository
        .findById(jobId)
        .ifPresent(
            definition -> {
              definition.setActive(false);
              repository.save(definition);
            });
    cancelScheduled(jobId);
    log.info("Paused job {}", jobId);
  }

  public List<ScheduledJobDefinition> listJobs() {
    return repository.findAll();
  }

  public void markRun(String jobId) {
    repository
        .findById(jobId)
        .ifPresent(
            definition -> {
              definition.setLastRunAt(OffsetDateTime.now());
              repository.save(definition);
            });
  }

  private ScheduledFuture<?> schedule(String jobId, String cronExpression, Runnable jobLogic) {
    return taskScheduler.schedule(
        () -> {
          try {
            jobLogic.run();
            markRun(jobId);
          } catch (Exception ex) {
            log.error("Job {} failed", jobId, ex);
          }
        },
        new CronTrigger(cronExpression));
  }

  private void cancelScheduled(String jobId) {
    ScheduledFuture<?> existing = scheduledHandles.remove(jobId);
    if (existing != null) {
      existing.cancel(false);
    }
  }
}
