package com.sentinel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 사용자. 가입은 관리자 초대로만 이루어지며 비밀번호는 해시로만 보관.
 */
@Entity
@Table(name = "app_user",
		uniqueConstraints = @UniqueConstraint(name = "uk_app_user_email", columnNames = { "email", "archive_key" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends ArchivableEntity {

	@Column(nullable = false, length = 255)
	private String email;

	@Column(nullable = false, length = 100)
	private String name;

	/** BCrypt 해시. 평문은 저장하지 않음 */
	@Column(nullable = false, length = 100)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	public User(String email, String name, String passwordHash, Role role) {
		this.email = normalizeEmail(email);
		this.name = Preconditions.requireText(name, "name", 100);
		this.passwordHash = Preconditions.requireText(passwordHash, "passwordHash", 100);
		this.role = role == null ? Role.MEMBER : role;
	}

	public void changePasswordHash(String passwordHash) {
		this.passwordHash = Preconditions.requireText(passwordHash, "passwordHash", 100);
	}

	public void rename(String name) {
		this.name = Preconditions.requireText(name, "name", 100);
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	/** 대소문자 차이로 같은 사람이 두 계정을 만들지 않도록 소문자로 보관 */
	public static String normalizeEmail(String email) {
		String value = Preconditions.requireText(email, "email", 255).toLowerCase();
		if (!value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw new IllegalArgumentException("email is not valid: " + value);
		}
		return value;
	}

	public enum Role {

		/** 사용자 초대·삭제 가능 */
		ADMIN,
		/** 프로젝트·실행 관리 */
		MEMBER

	}

}
