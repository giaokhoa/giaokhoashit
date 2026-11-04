package com.giaokhoa.server.tenancy;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dựng {@link TenantContext} cho request ngay sau khi cổng Resource Server đã xác thực JWT: alias trung tâm của
 * trang do tầng web đặt vào header nội bộ {@code X-LMS-Organization-Alias} (server không có cổng công khai nên chỉ
 * tầng web đặt được header này; thiếu header nghĩa là trang nền tảng), đối chiếu với claim của token tại
 * {@link TenantContextResolver}.
 */
public class TenantContextFilter extends OncePerRequestFilter {

	public static final String ORGANIZATION_ALIAS_HEADER = "X-LMS-Organization-Alias";

	private final TenantContextResolver resolver;

	public TenantContextFilter(TenantContextResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
				TenantContexts.set(this.resolver.resolve(jwt, request.getHeader(ORGANIZATION_ALIAS_HEADER)));
			}
			chain.doFilter(request, response);
		}
		catch (MembershipVerificationException ex) {
			// Không xác minh được membership hiện hành thì từ chối request tại đây (503), không cho request đi tiếp
			// với bất kỳ ngữ cảnh nào suy ra từ claim.
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		}
		finally {
			TenantContexts.clear();
		}
	}

}
