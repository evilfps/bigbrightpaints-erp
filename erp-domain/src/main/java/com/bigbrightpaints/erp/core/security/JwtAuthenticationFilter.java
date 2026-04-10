package com.bigbrightpaints.erp.core.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;

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

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

  private final JwtTokenService tokenService;
  private final UserAccountDetailsService userDetailsService;
  private final TokenBlacklistService blacklistService;
  private final ObjectProvider<RoleHierarchy> roleHierarchyProvider;

  public JwtAuthenticationFilter(
      JwtTokenService tokenService,
      UserAccountDetailsService userDetailsService,
      TokenBlacklistService blacklistService,
      ObjectProvider<RoleHierarchy> roleHierarchyProvider) {
    this.tokenService = tokenService;
    this.userDetailsService = userDetailsService;
    this.blacklistService = blacklistService;
    this.roleHierarchyProvider = roleHierarchyProvider;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      try {
        ValidatedToken validatedToken = resolveValidatedToken(request);
        if (validatedToken != null) {
          installAuthentication(validatedToken, request);
        }
      } catch (ExpiredJwtException e) {
        logger.debug("JWT token expired");
      } catch (MalformedJwtException e) {
        logger.warn("Malformed JWT token");
      } catch (SignatureException e) {
        logger.warn("Invalid JWT signature");
      } catch (UnsupportedJwtException e) {
        logger.warn("Unsupported JWT token");
      } catch (Exception e) {
        logger.error("JWT authentication error", e);
      }
    }
    filterChain.doFilter(request, response);
  }

  private ValidatedToken resolveValidatedToken(HttpServletRequest request) {
    String token = resolveToken(request);
    if (!StringUtils.hasText(token)) {
      return null;
    }
    return new ValidatedToken(token, tokenService.parse(token));
  }

  private void installAuthentication(ValidatedToken validatedToken, HttpServletRequest request) {
    Claims claims = validatedToken.claims();
    String tokenId = claims.getId();
    if (tokenId != null && blacklistService.isTokenBlacklisted(tokenId)) {
      logger.warn("Attempted use of blacklisted token");
      return;
    }

    String userId = claims.getSubject();
    Instant issuedAt = resolveTokenIssuedAt(claims);
    if (issuedAt != null && blacklistService.isUserTokenRevoked(userId, issuedAt)) {
      logger.warn("Attempted use of revoked user token");
      return;
    }

    request.setAttribute("jwtClaims", claims);
    UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(userId);
    if (!principal.isEnabled()) {
      logger.warn("Attempted use of token for disabled user");
      return;
    }
    if (!principal.isAccountNonLocked()) {
      logger.warn("Attempted use of token for locked user");
      return;
    }
    Collection<? extends GrantedAuthority> effectiveAuthorities = resolveAuthorities(principal);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, validatedToken.rawToken(), effectiveAuthorities);
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
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

  private Instant resolveTokenIssuedAt(Claims claims) {
    if (claims == null) {
      return null;
    }
    Number issuedAtMillis = claims.get("iatMs", Number.class);
    if (issuedAtMillis != null) {
      return Instant.ofEpochMilli(issuedAtMillis.longValue());
    }
    Date issuedAt = claims.getIssuedAt();
    return issuedAt != null ? issuedAt.toInstant() : null;
  }

  private record ValidatedToken(String rawToken, Claims claims) {}
}
