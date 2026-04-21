package com.bigbrightpaints.erp.core.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;

@Configuration
public class TenantSessionBindingDataSourceConfig {

  @Bean
  public static BeanPostProcessor tenantSessionBindingDataSourcePostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName)
          throws BeansException {
        if (!"dataSource".equals(beanName) || !(bean instanceof DataSource dataSource)) {
          return bean;
        }
        if (bean instanceof TenantSessionBindingDataSource) {
          return bean;
        }
        return new TenantSessionBindingDataSource(dataSource);
      }
    };
  }

  private static final class TenantSessionBindingDataSource extends DelegatingDataSource {

    private static final String SESSION_BIND_SQL =
        "SELECT set_config('app.current_company_id', ?, false)";
    private static final String SESSION_CLEAR_SQL =
        "SELECT set_config('app.current_company_id', '', false)";
    private static final String POSTGRESQL_PRODUCT = "postgresql";

    private TenantSessionBindingDataSource(DataSource targetDataSource) {
      super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return bindCompanyContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return bindCompanyContext(super.getConnection(username, password));
    }

    private Connection bindCompanyContext(Connection connection) throws SQLException {
      if (!isPostgreSql(connection)) {
        return connection;
      }
      String companyCode = normalizeCompanyCode(CompanyContextHolder.getCompanyCode());
      if (!StringUtils.hasText(companyCode)) {
        clearCompanyContext(connection);
        return connection;
      }
      applyCompanyContext(connection, companyCode);
      return connection;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
      String productName = connection.getMetaData().getDatabaseProductName();
      if (!StringUtils.hasText(productName)) {
        return false;
      }
      return productName.toLowerCase(Locale.ROOT).contains(POSTGRESQL_PRODUCT);
    }

    private void applyCompanyContext(Connection connection, String companyCode)
        throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(SESSION_BIND_SQL)) {
        statement.setString(1, companyCode);
        statement.execute();
      }
    }

    private void clearCompanyContext(Connection connection) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(SESSION_CLEAR_SQL)) {
        statement.execute();
      }
    }

    private String normalizeCompanyCode(String companyCode) {
      if (!StringUtils.hasText(companyCode)) {
        return null;
      }
      return companyCode.trim();
    }
  }
}
