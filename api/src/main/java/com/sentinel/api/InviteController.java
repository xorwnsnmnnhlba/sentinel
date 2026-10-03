package com.sentinel.api;

import com.sentinel.api.request.AcceptInviteRequest;
import com.sentinel.api.response.InviteResponse;
import com.sentinel.api.response.SessionResponse;
import com.sentinel.application.auth.AuthService;
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
 * 초대 링크로 계정 만들기. 로그인 전에 사용하므로 인증 없이 호출 가능 (토큰 자체가 자격증명)
 */
@RestController
@RequestMapping("/api/invites")
@RequiredArgsConstructor
class InviteController {

	private final UserService userService;

	/** 링크 확인. 만료·사용·위조는 모두 404로 동일하게 응답 */
	@GetMapping("/{token}")
	InviteResponse get(@PathVariable String token) {
		UserService.InviteInfo invite = userService.invite(token);
		return new InviteResponse(invite.email(), invite.expiresAt());
	}

	/** 수락. 계정을 만들고 바로 로그인 상태로 전환 */
	@PostMapping("/{token}")
	@ResponseStatus(HttpStatus.CREATED)
	SessionResponse accept(@PathVariable String token, @Valid @RequestBody AcceptInviteRequest request) {
		AuthService.Login login = userService.acceptInvite(token, request.name(), request.password());
		return new SessionResponse(login.token(), login.user());
	}
}
