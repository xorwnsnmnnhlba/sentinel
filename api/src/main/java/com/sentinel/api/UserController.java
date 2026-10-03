package com.sentinel.api;

import java.util.List;

import com.sentinel.api.request.ChangePasswordRequest;
import com.sentinel.api.request.CreateInviteRequest;
import com.sentinel.api.response.IssuedLinkResponse;
import com.sentinel.application.auth.AuthenticatedUser;
import com.sentinel.application.auth.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 관리. 목록·초대·삭제·재설정 발급은 관리자만 가능하고, 비밀번호 변경은 본인만 가능
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
class UserController {

	private final UserService userService;

	private final CurrentUser currentUser;

	@GetMapping
	List<UserService.UserSummary> list() {
		currentUser.requireAdmin();
		return userService.list();
	}

	/**
	 * 초대 링크 발급. 응답의 토큰으로 링크를 만들어 전달 (메일 발송 없음)
	 */
	@PostMapping("/invites")
	@ResponseStatus(HttpStatus.CREATED)
	IssuedLinkResponse invite(@Valid @RequestBody CreateInviteRequest request) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		UserService.IssuedToken issued = userService.invite(request.email(), request.role(), admin.id());
		return new IssuedLinkResponse(issued.token(), issued.expiresAt());
	}

	/**
	 * 비밀번호 재설정 링크 발급. 관리자가 새 비밀번호를 정하지 않고 사용자가 직접 입력
	 */
	@PostMapping("/{userId}/password-resets")
	@ResponseStatus(HttpStatus.CREATED)
	IssuedLinkResponse createPasswordReset(@PathVariable long userId) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		UserService.IssuedToken issued = userService.createPasswordReset(userId, admin.id());
		return new IssuedLinkResponse(issued.token(), issued.expiresAt());
	}

	/** 삭제(보관). 세션과 남은 링크를 함께 정리 */
	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long userId) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		userService.delete(userId, admin.id());
	}

	/** 본인 비밀번호 변경. 변경 후 모든 기기의 세션 종료 */
	@PostMapping("/me/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
		AuthenticatedUser user = currentUser.get();
		userService.changePassword(user.id(), request.currentPassword(), request.newPassword());
	}
}
