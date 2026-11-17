package com.fraudshield.common.security;

import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Every API needs an Entra ID access token for fraudshield-api, and roles come from its app roles.
 * Health and Prometheus endpoints are open; they are served on the management port, which no ingress exposes.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static HttpSecurity base(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable) // Stateless bearer tokens; no cookies are used.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated());
    }

    @Bean
    @Profile("!local & !test")
    SecurityFilterChain entraChain(HttpSecurity http) throws Exception {
        return base(http)
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new EntraConverter())))
                .build();
    }

    /** Local development: sign-in comes from X-Dev-User and X-Dev-Roles headers. Never active in Azure. */
    @Bean
    @Profile("local")
    SecurityFilterChain localChain(HttpSecurity http) throws Exception {
        return base(http)
                .addFilterBefore(new LocalDevAuthFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** Tests supply users with Spring Security's request post-processors. */
    @Bean
    @Profile("test")
    SecurityFilterChain testChain(HttpSecurity http) throws Exception {
        return base(http).build();
    }

    /** Uses the person's UPN (or the workload's subject) as the user ID, and Entra app roles as authorities. */
    static class EntraConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> authorities = (roles == null ? List.<String>of() : roles).stream()
                    .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
            String name = jwt.getClaimAsString("preferred_username");
            return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
        }
    }
}