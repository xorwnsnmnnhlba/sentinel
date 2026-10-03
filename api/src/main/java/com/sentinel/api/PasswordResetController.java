package com.sentinel.api;

import com.sentinel.api.request.ResetPasswordRequest;
import com.sentinel.api.response.PasswordResetResponse;
import com.sentinel.application.auth.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재설정 링크로 비밀번호 변경. 로그인할 수 없는 상태에서 사용하므로 인증 없이 호출 가능
 */
@RestController
@RequestMapping("/api/password-resets")
@RequiredArgsConstructor
class PasswordResetController {

	private final UserService userService;

	/** 링크 확인. 만료·사용·위조는 모두 404 */
	@GetMapping("/{token}")
	PasswordResetResponse get(@PathVariable String token) {
		UserService.ResetInfo reset = userService.passwordReset(token);
		return new PasswordResetResponse(reset.email(), reset.expiresAt());
	}

	/** 새 비밀번호 설정. 기존 세션은 모두 종료되므로 다시 로그인 필요 */
	@PostMapping("/{token}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void reset(@PathVariable String token, @Valid @RequestBody ResetPasswordRequest request) {
		userService.resetPassword(token, request.password());
	}
}
