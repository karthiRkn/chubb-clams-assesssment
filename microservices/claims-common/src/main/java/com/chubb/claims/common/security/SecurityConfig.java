package com.chubb.claims.common.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security chain every service shares: stateless, no sessions, no CSRF (there are no cookies to forge), bearer
 * tokens only, and the service's own {@link AccessRules} as the authorization table. The rules, in order:
 * <ol>
 *   <li>Swagger, the API docs and the health endpoint ({@code /actuator/health}, which reports only UP or DOWN) stay
 *       open so the API can be explored and a service can be probed without a token;</li>
 *   <li>{@code /internal/v1/**} accepts only a SERVICE token, for every service, so no service can forget it;</li>
 *   <li>the service's own rules;</li>
 *   <li>anything else is ADMIN-only, so a route added without a rule fails closed (and a missing token is still 401).</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtUtil jwt, ObjectProvider<AccessRules> accessRules,
                                                   RestSecurityHandlers handlers) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterBefore(new JwtFilter(jwt), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs", "/v3/api-docs/**", "/error",
                            "/actuator/health", "/actuator/health/**").permitAll();
                    requests.requestMatchers("/internal/v1/**").hasRole(Role.SERVICE.name());
                    accessRules.orderedStream().flatMap(r -> r.rules().stream()).forEach(rule -> apply(requests, rule));
                    requests.anyRequest().hasRole(Role.ADMIN.name());
                });
        return http.build();
    }

    /**
     * Logging in is done by the User Service itself, never through Spring's authentication manager chain. Declaring one
     * that refuses everything stops Spring Boot from creating its default in-memory user (with a generated password that
     * it prints to the log) in every service.
     */
    @Bean
    public AuthenticationManager rejectingAuthenticationManager() {
        return authentication -> {
            throw new ProviderNotFoundException("Authentication is by Bearer token only");
        };
    }

    private static void apply(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests,
                              AccessRule rule) {
        AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizedUrl url = rule.method() == null
                ? requests.requestMatchers(rule.pattern())
                : requests.requestMatchers(rule.method(), rule.pattern());
        if (rule.open()) {
            url.permitAll();
        } else {
            url.hasAnyRole(rule.roles().stream().map(Role::name).toArray(String[]::new));
        }
    }
}
