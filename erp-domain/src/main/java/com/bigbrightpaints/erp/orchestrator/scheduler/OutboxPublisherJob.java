package com.bigbrightpaints.erp.orchestrator.scheduler;

import com.bigbrightpaints.erp.orchestrator.service.EventPublisherService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);
    private final EventPublisherService eventPublisherService;
    private final SchedulerService schedulerService;
    private final String cronExpression;

    public OutboxPublisherJob(EventPublisherService eventPublisherService, SchedulerService schedulerService,
                              @Value("${orchestrator.outbox.cron:0/30 * * * * *}") String cronExpression) {
        this.eventPublisherService = eventPublisherService;
        this.schedulerService = schedulerService;
        this.cronExpression = cronExpression;
    }

    @PostConstruct
    public void schedule() {
        schedulerService.registerJob("outbox-publisher", cronExpression, this::publishOnce,
            "Publish pending outbox events", "orchestrator");
    }

    public void publishOnce() {
        log.debug("Publishing pending outbox events");
        eventPublisherService.publishPendingEvents();
    }
}
