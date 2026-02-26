package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.service.UserAccountDetailsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenService tokenService;
    private final UserAccountDetailsService userDetailsService;
    private final TokenBlacklistService blacklistService;
    private final ObjectProvider<RoleHierarchy> roleHierarchyProvider;

    public JwtAuthenticationFilter(JwtTokenService tokenService,
                                 UserAccountDetailsService userDetailsService,
                                 TokenBlacklistService blacklistService,
                                 ObjectProvider<RoleHierarchy> roleHierarchyProvider) {
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
        this.blacklistService = blacklistService;
        this.roleHierarchyProvider = roleHierarchyProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = tokenService.parse(token);

                // Check if token is blacklisted
                String tokenId = claims.getId();
                if (tokenId != null && blacklistService.isTokenBlacklisted(tokenId)) {
                    logger.warn("Attempted use of blacklisted token - JTI: {}, User: {}, IP: {}",
                               tokenId, claims.getSubject(), request.getRemoteAddr());
                    filterChain.doFilter(request, response);
                    return;
                }

                // Check if user's tokens are revoked
                String userId = claims.getSubject();
                Date issuedAt = claims.getIssuedAt();
                if (issuedAt != null && blacklistService.isUserTokenRevoked(userId, issuedAt.toInstant())) {
                    logger.warn("Attempted use of revoked user token - User: {}, IP: {}",
                               userId, request.getRemoteAddr());
                    filterChain.doFilter(request, response);
                    return;
                }

                request.setAttribute("jwtClaims", claims);
                UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(claims.getSubject());
                if (!principal.isEnabled()) {
                    logger.warn("Attempted use of token for disabled user - User: {}, IP: {}",
                            claims.getSubject(), request.getRemoteAddr());
                    filterChain.doFilter(request, response);
                    return;
                }
                Collection<? extends GrantedAuthority> effectiveAuthorities = resolveAuthorities(principal);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, token, effectiveAuthorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (ExpiredJwtException e) {
                logger.debug("JWT token expired - User: {}, IP: {}",
                           e.getClaims().getSubject(), request.getRemoteAddr());
            } catch (MalformedJwtException e) {
                logger.warn("Malformed JWT token - IP: {}", request.getRemoteAddr());
            } catch (SignatureException e) {
                logger.warn("Invalid JWT signature - IP: {}", request.getRemoteAddr());
            } catch (UnsupportedJwtException e) {
                logger.warn("Unsupported JWT token - IP: {}", request.getRemoteAddr());
            } catch (Exception e) {
                logger.error("JWT authentication error - IP: {}", request.getRemoteAddr(), e);
            }
        }
        filterChain.doFilter(request, response);
    }

    private Collection<? extends GrantedAuthority> resolveAuthorities(UserPrincipal principal) {
        Collection<? extends GrantedAuthority> directAuthorities = principal.getAuthorities();
        RoleHierarchy roleHierarchy = roleHierarchyProvider.getIfAvailable();
        if (roleHierarchy == null) {
            return directAuthorities;
        }
        return roleHierarchy.getReachableGrantedAuthorities(directAuthorities);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
