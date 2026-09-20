package com.sentinel.api;

import java.time.Instant;
import java.util.List;

import com.sentinel.application.auth.AuthenticatedUser;
import com.sentinel.application.auth.UserService;
import com.sentinel.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
	IssuedLink invite(@Valid @RequestBody InviteRequest request) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		UserService.IssuedToken issued = userService.invite(request.email(), request.role(), admin.id());
		return new IssuedLink(issued.token(), issued.expiresAt());
	}

	/**
	 * 비밀번호 재설정 링크 발급. 관리자가 새 비밀번호를 정하지 않고 사용자가 직접 입력
	 */
	@PostMapping("/{userId}/password-resets")
	@ResponseStatus(HttpStatus.CREATED)
	IssuedLink createPasswordReset(@PathVariable long userId) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		UserService.IssuedToken issued = userService.createPasswordReset(userId, admin.id());
		return new IssuedLink(issued.token(), issued.expiresAt());
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

	record InviteRequest(@Email @NotBlank String email, User.Role role) {
	}

	record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
	}

	/**
	 * @param token 링크에 담을 토큰. 저장하지 않으므로 이 응답에서만 확인 가능
	 */
	record IssuedLink(String token, Instant expiresAt) {
	}

}
