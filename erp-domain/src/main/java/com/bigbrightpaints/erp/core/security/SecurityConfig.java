package com.bigbrightpaints.erp.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.bigbrightpaints.erp.modules.auth.service.UserAccountDetailsService;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final RequestBodyCachingFilter requestBodyCachingFilter;
  private final CompanyContextFilter companyContextFilter;
  private final MustChangePasswordCorridorFilter mustChangePasswordCorridorFilter;
  private final AuditAwareAccessDeniedHandler auditAwareAccessDeniedHandler;
  private final UserAccountDetailsService userDetailsService;
  private final Environment environment;
  private final boolean swaggerPublic;

  @Autowired
  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RequestBodyCachingFilter requestBodyCachingFilter,
      CompanyContextFilter companyContextFilter,
      MustChangePasswordCorridorFilter mustChangePasswordCorridorFilter,
      AuditAwareAccessDeniedHandler auditAwareAccessDeniedHandler,
      UserAccountDetailsService userDetailsService,
      @Autowired(required = false) Environment environment,
      @Value("${erp.security.swagger-public:false}") boolean swaggerPublic) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.requestBodyCachingFilter = requestBodyCachingFilter;
    this.companyContextFilter = companyContextFilter;
    this.mustChangePasswordCorridorFilter = mustChangePasswordCorridorFilter;
    this.auditAwareAccessDeniedHandler = auditAwareAccessDeniedHandler;
    this.userDetailsService = userDetailsService;
    this.environment = environment;
    this.swaggerPublic = swaggerPublic;
  }

  SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RequestBodyCachingFilter requestBodyCachingFilter,
      CompanyContextFilter companyContextFilter,
      MustChangePasswordCorridorFilter mustChangePasswordCorridorFilter,
      AuditAwareAccessDeniedHandler auditAwareAccessDeniedHandler,
      UserAccountDetailsService userDetailsService,
      boolean swaggerPublic) {
    this(
        jwtAuthenticationFilter,
        requestBodyCachingFilter,
        companyContextFilter,
        mustChangePasswordCorridorFilter,
        auditAwareAccessDeniedHandler,
        userDetailsService,
        null,
        swaggerPublic);
  }

  /**
   * Configures the security filter chain.
   * <p>
   * CSRF Protection: Disabled because this is a stateless REST API using JWT Bearer tokens.
   * CSRF attacks exploit automatic cookie transmission by browsers, but JWT tokens must be
   * explicitly included in the Authorization header, making CSRF protection unnecessary.
   * All authentication is purely token-based with no session cookies involved.
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.cors(Customizer.withDefaults())
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            exception -> exception.accessDeniedHandler(auditAwareAccessDeniedHandler))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            registry -> {
              registry
                  .requestMatchers(HttpMethod.OPTIONS, "/**")
                  .permitAll()
                  .requestMatchers(
                      "/api/v1/auth/login",
                      "/api/v1/auth/refresh-token",
                      "/api/v1/auth/password/forgot",
                      "/api/v1/auth/password/reset")
                  .permitAll()
                  // Keep retired tenant-admin hosts unresolved (dispatcher 404) for every caller.
                  .requestMatchers(RetiredTenantAdminHostPaths.requestMatchers())
                  .permitAll();
              if (isSwaggerAllowed()) {
                registry
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs.yaml")
                    .permitAll();
              }
              registry
                  .requestMatchers(
                      HttpMethod.POST,
                      "/api/v1/accounting/receipts/dealer",
                      "/api/v1/accounting/settlements/dealers",
                      "/api/v1/accounting/settlements/suppliers")
                  .hasAnyAuthority("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SUPER_ADMIN")
                  .requestMatchers("/actuator/health", "/actuator/health/**")
                  .permitAll()
                  .anyRequest()
                  .authenticated();
            })
        .userDetailsService(userDetailsService)
        .addFilterBefore(requestBodyCachingFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(jwtAuthenticationFilter, RequestBodyCachingFilter.class)
        .addFilterAfter(companyContextFilter, JwtAuthenticationFilter.class)
        .addFilterAfter(mustChangePasswordCorridorFilter, CompanyContextFilter.class);
    return http.build();
  }

  @Bean
  public static PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  private boolean isSwaggerAllowed() {
    if (!swaggerPublic) {
      return false;
    }
    boolean productionProfileActive =
        environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    if (productionProfileActive) {
      log.warn(
          "Ignoring erp.security.swagger-public=true because prod profile is active;"
              + " Swagger/OpenAPI endpoints remain secured");
      return false;
    }
    return true;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  public RoleHierarchy roleHierarchy() {
    RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
    hierarchy.setHierarchy("ROLE_SUPER_ADMIN > ROLE_ADMIN");
    return hierarchy;
  }

  @Bean
  public MethodSecurityExpressionHandler methodSecurityExpressionHandler(
      RoleHierarchy roleHierarchy) {
    DefaultMethodSecurityExpressionHandler expressionHandler =
        new DefaultMethodSecurityExpressionHandler();
    expressionHandler.setRoleHierarchy(roleHierarchy);
    return expressionHandler;
  }
}
