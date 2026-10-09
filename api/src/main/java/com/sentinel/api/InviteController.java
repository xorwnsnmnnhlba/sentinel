package com.sentinel.api;

import java.net.URI;

import com.sentinel.api.request.CreateInviteRequest;
import com.sentinel.api.response.InviteResponse;
import com.sentinel.api.response.IssuedLinkResponse;
import com.sentinel.application.auth.AuthenticatedUser;
import com.sentinel.application.auth.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 초대. 관리자가 발급하고, 받은 사람은 로그인 전에 확인 (토큰 자체가 자격증명). 수락은 계정 만들기({@code POST /api/users})
 */
@RestController
@RequestMapping("/api/invites")
@RequiredArgsConstructor
class InviteController {

	private final UserService userService;

	private final CurrentUser currentUser;

	/**
	 * 발급 (관리자). 응답의 토큰으로 링크를 만들어 전달 (메일 발송 없음). 토큰은 이 응답에서만 확인 가능
	 */
	@PostMapping
	ResponseEntity<IssuedLinkResponse> create(@Valid @RequestBody CreateInviteRequest request) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		UserService.IssuedToken issued = userService.invite(request.email(), request.role(), admin.id());
		return ResponseEntity.created(URI.create("/api/invites/" + issued.token()))
			.body(new IssuedLinkResponse(issued.token(), issued.expiresAt()));
	}

	/** 확인 (로그인 불필요). 만료·사용·위조는 모두 404로 동일하게 응답 */
	@GetMapping("/{token}")
	InviteResponse get(@PathVariable String token) {
		UserService.InviteInfo invite = userService.invite(token);
		return new InviteResponse(invite.email(), invite.expiresAt());
	}

}
