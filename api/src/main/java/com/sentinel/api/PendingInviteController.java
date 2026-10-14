package com.sentinel.api;

import java.util.List;

import com.sentinel.application.auth.AuthenticatedUser;
import com.sentinel.application.auth.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기 중인 초대 (관리자). 발급({@code POST /api/invites})했지만 수락·만료·취소되지 않은 초대를 보고 취소.
 * 초대 링크 확인({@code GET /api/invites/{token}})은 로그인 없이 열려 있어, 관리 기능은 숫자 ID를 쓰는 별도 경로로 둠
 * 링크(토큰)는 발급 응답에서만 볼 수 있으므로 포함하지 않음
 */
@RestController
@Tag(name = "초대")
@RequestMapping("/api/pending-invites")
@RequiredArgsConstructor
class PendingInviteController {

	private final UserService userService;

	private final CurrentUser currentUser;

	/** 최근 발급 순 */
	@GetMapping
	List<UserService.PendingInvite> list() {
		currentUser.requireAdmin();
		return userService.pendingInvites();
	}

	/** 없거나 수락·만료·취소된 초대는 404 */
	@GetMapping("/{inviteId}")
	UserService.PendingInvite get(@PathVariable long inviteId) {
		currentUser.requireAdmin();
		return userService.pendingInvite(inviteId);
	}

	/** 취소. 보낸 링크는 바로 쓸 수 없게 됨 (확인·수락 모두 404). 없거나 수락·만료·취소된 초대는 404 */
	@DeleteMapping("/{inviteId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revoke(@PathVariable long inviteId) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		userService.revokeInvite(inviteId, admin.id());
	}

}
