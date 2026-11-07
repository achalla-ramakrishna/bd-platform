package com.codewalnut.bd.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Google Workspace sign-in (ADR-0004). API calls without a session get 401 instead of a redirect,
 * so the React app can send the user to sign in. CSRF uses the SPA cookie pattern.
 *
 * With {@code app.security.dev-login=true} (local development and tests only), a form login accepts
 * any email in the allowed domain with the configured password, in place of Google.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties props, UserDirectory users) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg", "/signed-out").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .csrf(csrf -> csrf.spa())
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/signed-out"));

        if (props.devLogin()) {
            http.formLogin(form -> form.defaultSuccessUrl("/", true));
        } else {
            http.oauth2Login(oauth -> oauth
                .userInfoEndpoint(ui -> ui.oidcUserService(new WorkspaceOidcUserService(props, users)))
                .defaultSuccessUrl("/", true));
        }
        return http.build();
    }

    /** Development-only users: any allowed-domain email with the dev password. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.security.dev-login", havingValue = "true")
    UserDetailsService devUsers(SecurityProperties props) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String hash = encoder.encode(props.devPassword());
        return new InMemoryUserDetailsManager() {
            @Override
            public org.springframework.security.core.userdetails.UserDetails loadUserByUsername(String username) {
                if (!props.isAllowedEmail(username)) {
                    throw new org.springframework.security.core.userdetails.UsernameNotFoundException(username);
                }
                return User.withUsername(username.toLowerCase()).password(hash).roles("USER").build();
            }
        };
    }
}
