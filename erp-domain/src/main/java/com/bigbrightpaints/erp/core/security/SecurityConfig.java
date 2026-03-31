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
  private final CompanyContextFilter companyContextFilter;
  private final MustChangePasswordCorridorFilter mustChangePasswordCorridorFilter;
  private final UserAccountDetailsService userDetailsService;
  private final Environment environment;
  private final boolean swaggerPublic;

  @Autowired
  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      CompanyContextFilter companyContextFilter,
      MustChangePasswordCorridorFilter mustChangePasswordCorridorFilter,
      UserAccountDetailsService userDetailsService,
      @Autowired(required = false) Environment environment,
      @Value("${erp.security.swagger-public:false}") boolean swaggerPublic) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.companyContextFilter = companyContextFilter;
    this.mustChangePasswordCorridorFilter = mustChangePasswordCorridorFilter;
    this.userDetailsService = userDetailsService;
    this.environment = environment;
    this.swaggerPublic = swaggerPublic;
  }

  SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      CompanyContextFilter companyContextFilter,
      MustChangePasswordCorridorFilter mustChangePasswordCorridorFilter,
      UserAccountDetailsService userDetailsService,
      boolean swaggerPublic) {
    this(
        jwtAuthenticationFilter,
        companyContextFilter,
        mustChangePasswordCorridorFilter,
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
                  .permitAll();
              if (isSwaggerAllowed()) {
                registry
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs.yaml")
                    .permitAll();
              }
              registry
                  .requestMatchers("/actuator/health", "/actuator/health/**")
                  .permitAll()
                  .anyRequest()
                  .authenticated();
            })
        .userDetailsService(userDetailsService)
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
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
