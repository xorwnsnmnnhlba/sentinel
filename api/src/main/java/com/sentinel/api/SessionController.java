package com.sentinel.api;

import com.sentinel.api.request.CreateSessionRequest;
import com.sentinel.api.response.SessionResponse;
import com.sentinel.application.auth.AuthService;
import com.sentinel.application.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 세션. 브라우저 쿠키는 Next.js(BFF)가 관리하고 Spring은 토큰만 주고받음
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
class SessionController {

	private final AuthService authService;

	private final CurrentUser currentUser;

	/**
	 * 로그인. 응답의 토큰은 이후 {@code Authorization: Bearer <토큰>}으로 전달
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SessionResponse create(@Valid @RequestBody CreateSessionRequest request) {
		AuthService.Login login = authService.login(request.email(), request.password());
		return new SessionResponse(login.token(), login.user());
	}

	/** 현재 세션 사용자 */
	@GetMapping("/current")
	AuthenticatedUser current() {
		return currentUser.get();
	}

	/** 로그아웃. 세션을 즉시 폐기하여 같은 토큰을 다시 쓸 수 없게 함 */
	@DeleteMapping("/current")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@RequestHeader(name = "Authorization", required = false) String authorization) {
		if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			authService.logout(authorization.substring(7).trim());
		}
	}
}
