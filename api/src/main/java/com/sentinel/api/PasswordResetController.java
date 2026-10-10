package com.sentinel.api;

import java.net.URI;

import com.sentinel.api.request.CreatePasswordResetRequest;
import com.sentinel.api.response.IssuedLinkResponse;
import com.sentinel.api.response.PasswordResetResponse;
import com.sentinel.application.IdName;
import com.sentinel.application.auth.AuthenticatedUser;
import com.sentinel.application.auth.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 재설정 링크. 관리자가 발급하고, 받은 사람은 로그인할 수 없는 상태에서 확인 (토큰 자체가 자격증명).
 * 새 비밀번호 설정은 {@code PUT /api/users/{userId}/password}
 */
@RestController
@Tag(name = "비밀번호 재설정")
@RequestMapping("/api/password-resets")
@RequiredArgsConstructor
class PasswordResetController {

	private final UserService userService;

	private final CurrentUser currentUser;

	/**
	 * 발급 (관리자). 관리자가 새 비밀번호를 정하지 않고 사용자가 직접 입력. 토큰은 이 응답에서만 확인 가능
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping
	ResponseEntity<IssuedLinkResponse> create(@Valid @RequestBody CreatePasswordResetRequest request) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		UserService.IssuedToken issued = userService.createPasswordReset(request.userId(), admin.id());
		return ResponseEntity.created(URI.create("/api/password-resets/" + issued.token()))
			.body(new IssuedLinkResponse(issued.token(), issued.expiresAt()));
	}

	/** 확인 (로그인 불필요). 만료·사용·위조는 모두 404 */
	// 로그인 없이 호출 (API 문서에 인증 표시 없음)
	@SecurityRequirements
	@GetMapping("/{token}")
	PasswordResetResponse get(@PathVariable String token) {
		UserService.ResetInfo reset = userService.passwordReset(token);
		return new PasswordResetResponse(new IdName(reset.userId(), reset.email()), reset.expiresAt());
	}

}
