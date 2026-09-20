package com.sentinel.infra.security;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import com.sentinel.application.auth.AuthService;
import com.sentinel.application.auth.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 세션 토큰 확인. 브라우저는 Next.js(BFF)를 거치므로 Spring은 쿠키가 아닌
 * {@code Authorization: Bearer <토큰>} 헤더만 받음.
 * <p>
 * 로그인 API만 인증 없이 허용하고 나머지 {@code /api/**}는 세션 필요. 확인된 사용자는 요청 속성에 담아 컨트롤러에 전달.
 */
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

	/** 컨트롤러가 현재 사용자를 읽는 요청 속성 이름 */
	public static final String USER_ATTRIBUTE = "sentinel.user";

	private static final String LOGIN_PATH = "/api/sessions";

	private static final Set<String> OPEN_METHODS = Set.of("OPTIONS");

	private final AuthService authService;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<AuthenticatedUser> user = authService.authenticate(bearerToken(request));
		user.ifPresent(authenticated -> request.setAttribute(USER_ATTRIBUTE, authenticated));

		if (user.isEmpty() && requiresAuthentication(request)) {
			writeUnauthorized(response);
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean requiresAuthentication(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (!path.startsWith("/api/") || OPEN_METHODS.contains(request.getMethod())) {
			return false;
		}
		// 로그인 요청만 예외. 로그아웃은 토큰이 있어야 처리 가능
		return !(LOGIN_PATH.equals(path) && "POST".equals(request.getMethod()));
	}

	private static String bearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}
		return header.substring(7).trim();
	}

	/** ProblemDetail 형식은 ApiExceptionHandler와 동일하게 유지 */
	private static void writeUnauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter()
			.write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
					+ "\"detail\":\"authentication required\"}");
	}

}
