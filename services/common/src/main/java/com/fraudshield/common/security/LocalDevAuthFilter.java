package com.fraudshield.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Trusts X-Dev-User and X-Dev-Roles headers. Registered only by the local profile's filter chain. */
class LocalDevAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getHeader("X-Dev-User");
        if (user != null && !user.isBlank()) {
            String header = request.getHeader("X-Dev-Roles");
            List<SimpleGrantedAuthority> authorities = Arrays.stream(header == null ? new String[0] : header.split(","))
                    .map(String::trim).filter(r -> !r.isEmpty())
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
        }
        chain.doFilter(request, response);
    }
}