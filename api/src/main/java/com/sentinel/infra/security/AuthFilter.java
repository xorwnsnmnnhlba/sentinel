package com.sentinel.infra.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.sentinel.application.auth.AccessTokenService;
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
 * <p>
 * API 토큰({@code snt_} 접두어, CI 등 자동화용)은 실행 시작({@code POST /api/runs})과 그 결과 조회({@code GET /api/runs/{id}})만
 * 허용하고 그 외는 403. 토큰이 새도 케이스·환경 수정, 변수 조회, 사용자 관리, 토큰 발급은 불가
 */
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

	/** 컨트롤러가 현재 사용자를 읽는 요청 속성 이름 */
	public static final String USER_ATTRIBUTE = "sentinel.user";

	/**
	 * 로그인 전에 쓰는 요청. 로그인 자체와, 초대·재설정 토큰이 자격증명인 요청 (토큰은 만료·1회용, 서비스가 확인)
	 * 메서드와 경로가 모두 맞아야 함 (같은 경로의 다른 메서드는 인증 필요)
	 */
	private static final List<OpenRoute> OPEN_ROUTES = List.of(new OpenRoute("POST", "/api/sessions"),
			new OpenRoute("GET", "/api/invites/[^/]+"), new OpenRoute("GET", "/api/password-resets/[^/]+"),
			// 초대 수락 (계정 만들기)
			new OpenRoute("POST", "/api/users"),
			// 재설정 링크로 새 비밀번호 설정
			new OpenRoute("PUT", "/api/users/\\d+/password"));

	private static final Set<String> OPEN_METHODS = Set.of("OPTIONS");

	/**
	 * 경로 해석에 따라 다른 API로 닿을 수 있는 표기. 점 세그먼트, 경로 매개변수(;), 인코딩된 . / \, 빈 세그먼트(//).
	 * 이런 경로는 링크 API로 보지 않고 항상 인증 요구 (라우팅의 경로 정리 방식에 기대지 않음)
	 */
	private static final Pattern AMBIGUOUS_PATH = Pattern.compile("(^|/)\\.{1,2}(/|$)|;|%2[eEfF]|%5[cC]|//");

	/** API 토큰으로 부를 수 있는 요청. 숫자 ID만 (다른 경로 표기로 우회 불가) */
	private static final Pattern RUN_DETAIL = Pattern.compile("^/api/runs/\\d+$");

	private final AuthService authService;

	private final AccessTokenService accessTokens;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = bearerToken(request);
		// 접두어로 먼저 구분하여 세션 요청마다 토큰 조회 트랜잭션을 열지 않음
		Optional<AuthenticatedUser> viaAccessToken = token != null && token.startsWith(AccessTokenService.PREFIX)
				? accessTokens.authenticate(token) : Optional.empty();
		Optional<AuthenticatedUser> user = viaAccessToken.isPresent() ? viaAccessToken
				: authService.authenticate(token);
		user.ifPresent(authenticated -> request.setAttribute(USER_ATTRIBUTE, authenticated));

		if (user.isEmpty() && requiresAuthentication(request)) {
			writeUnauthorized(response);
			return;
		}
		if (viaAccessToken.isPresent() && !allowedForAccessToken(request)) {
			writeProblem(response, HttpStatus.FORBIDDEN, "access tokens can only start runs and read them");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean requiresAuthentication(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (!path.startsWith("/api/") || OPEN_METHODS.contains(request.getMethod())) {
			return false;
		}
		// 로그아웃(DELETE /api/sessions/current)은 토큰이 있어야 처리 가능하므로 열지 않음
		return AMBIGUOUS_PATH.matcher(path).find()
				|| OPEN_ROUTES.stream().noneMatch(route -> route.matches(request.getMethod(), path));
	}

	private record OpenRoute(String method, Pattern path) {

		OpenRoute(String method, String path) {
			this(method, Pattern.compile("^" + path + "$"));
		}

		boolean matches(String requestMethod, String requestPath) {
			return method.equals(requestMethod) && path.matcher(requestPath).matches();
		}

	}

	private static String bearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}
		return header.substring(7).trim();
	}

	private static boolean allowedForAccessToken(HttpServletRequest request) {
		String path = request.getRequestURI();
		return switch (request.getMethod()) {
			case "POST" -> "/api/runs".equals(path);
			case "GET" -> RUN_DETAIL.matcher(path).matches();
			default -> false;
		};
	}

	private static void writeUnauthorized(HttpServletResponse response) throws IOException {
		writeProblem(response, HttpStatus.UNAUTHORIZED, "authentication required");
	}

	/** ProblemDetail 형식은 ApiExceptionHandler와 동일하게 유지 */
	private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter()
			.write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
					+ status.value() + ",\"detail\":\"" + detail + "\"}");
	}

}
