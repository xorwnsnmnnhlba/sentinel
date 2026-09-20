package com.sentinel.application.auth;

import com.sentinel.domain.User;

/**
 * 요청을 수행하는 사용자. 비밀번호 해시 등 민감한 값은 포함하지 않음.
 */
public record AuthenticatedUser(long id, String email, String name, User.Role role) {

	static AuthenticatedUser of(User user) {
		return new AuthenticatedUser(user.getId(), user.getEmail(), user.getName(), user.getRole());
	}

	public boolean isAdmin() {
		return role == User.Role.ADMIN;
	}

}
