package com.bigbrightpaints.erp.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.User;
import io.sentry.spring.jakarta.SentrySpringFilter;

@Configuration
@ConditionalOnProperty(name = "sentry.dsn")
public class SentryConfig {

  @Bean
  public SentryOptions.BeforeSendCallback beforeSendCallback() {
    return (SentryEvent event, Hint hint) -> {
      enrichWithTenantContext(event);
      enrichWithUserContext(event);
      return event;
    };
  }

  @Bean
  public SentrySpringFilter sentrySpringFilter() {
    return new SentrySpringFilter();
  }

  private void enrichWithTenantContext(SentryEvent event) {
    String companyCode = CompanyContextHolder.getCompanyCode();
    if (companyCode != null) {
      event.setTag("tenant", companyCode);
    }
  }

  private void enrichWithUserContext(SentryEvent event) {
    try {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
        User sentryUser = event.getUser();
        if (sentryUser == null) {
          sentryUser = new User();
        }
        sentryUser.setUsername(auth.getName());
        event.setUser(sentryUser);
      }
    } catch (Exception ignored) {
      // Security context not available outside request scope
    }
  }
}
