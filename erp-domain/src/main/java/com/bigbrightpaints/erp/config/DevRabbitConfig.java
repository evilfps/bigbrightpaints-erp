package com.bigbrightpaints.erp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Dev-only stub to satisfy RabbitTemplate dependency without requiring a broker.
 */
@Configuration
@Profile({"dev", "openapi"})
public class DevRabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(DevRabbitConfig.class);

    @Bean
    @Primary
    public RabbitTemplate rabbitTemplate() {
        return new NoOpRabbitTemplate();
    }

    private static class NoOpRabbitTemplate extends RabbitTemplate {
        @Override
        public void afterPropertiesSet() {
            // Skip ConnectionFactory validation in dev/no-broker mode
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message) {
            log.debug("Dev NoOpRabbitTemplate: skipping send to exchange={}, routingKey={}", exchange, routingKey);
        }
    }
}
