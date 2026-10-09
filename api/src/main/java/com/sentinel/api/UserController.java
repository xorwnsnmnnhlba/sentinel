package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.ChangePasswordRequest;
import com.sentinel.api.request.CreateUserRequest;
import com.sentinel.api.request.ResetPasswordRequest;
import com.sentinel.application.auth.AuthenticatedUser;
import com.sentinel.application.auth.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자. 목록·조회·삭제는 관리자만, 비밀번호 변경은 본인만. 계정 만들기(초대 수락)와 재설정 링크로 비밀번호 설정은 로그인 전에 토큰으로
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

	/** 관리자만 (목록과 같음) */
	@GetMapping("/{userId}")
	UserService.UserSummary get(@PathVariable long userId) {
		currentUser.requireAdmin();
		return userService.get(userId);
	}

	/**
	 * 초대 수락으로 계정 만들기 (로그인 불필요, 초대 토큰이 자격증명). 이메일·역할은 초대에 따름. 로그인은 {@code POST /api/sessions}로 따로
	 * 만료·사용·위조된 초대는 404
	 */
	@PostMapping
	ResponseEntity<UserService.UserSummary> create(@Valid @RequestBody CreateUserRequest request) {
		UserService.UserSummary created = userService.acceptInvite(request.inviteToken(), request.name(),
				request.password());
		return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
	}

	/** 삭제(보관). 세션과 남은 링크를 함께 정리 */
	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long userId) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		userService.delete(userId, admin.id());
	}

	/** 본인 비밀번호 변경. 현재 비밀번호 확인, 변경 후 모든 기기의 세션 종료 */
	@PutMapping("/me/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
		AuthenticatedUser user = currentUser.get();
		userService.changePassword(user.id(), request.currentPassword(), request.newPassword());
	}

	/**
	 * 재설정 링크로 새 비밀번호 설정 (로그인 불필요, 재설정 토큰이 자격증명). 기존 세션은 모두 종료되므로 다시 로그인 필요.
	 * 만료·사용·위조된 링크나 다른 사용자의 링크는 404
	 */
	@PutMapping("/{userId}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@PathVariable long userId, @Valid @RequestBody ResetPasswordRequest request) {
		userService.resetPassword(userId, request.resetToken(), request.password());
	}

}
