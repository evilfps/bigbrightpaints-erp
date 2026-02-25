package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.modules.auth.service.UserAccountDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.Customizer;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CompanyContextFilter companyContextFilter;
    private final UserAccountDetailsService userDetailsService;
    private final boolean swaggerPublic;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CompanyContextFilter companyContextFilter,
                          UserAccountDetailsService userDetailsService,
                          @Value("${erp.security.swagger-public:false}") boolean swaggerPublic) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.companyContextFilter = companyContextFilter;
        this.userDetailsService = userDetailsService;
        this.swaggerPublic = swaggerPublic;
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
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(registry -> {
                registry.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh-token",
                                "/api/v1/auth/password/forgot",
                                "/api/v1/auth/password/reset"
                        ).permitAll();
                if (swaggerPublic) {
                    registry.requestMatchers(
                            "/swagger-ui/**",
                            "/v3/api-docs/**",
                            "/v3/api-docs.yaml"
                    ).permitAll();
                }
                registry.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated();
            })
            .userDetailsService(userDetailsService)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(companyContextFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
