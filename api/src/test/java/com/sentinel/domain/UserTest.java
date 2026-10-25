package com.sentinel.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

	@Test
	void keepsEmailsTrimmedAndLowerCase() {
		assertThat(User.normalizeEmail("  Member@Sentinel.TEST ")).isEqualTo("member@sentinel.test");
	}

	@Test
	void tellsAdministratorsFromMembers() {
		assertThat(new User("admin@sentinel.test", "관리자", "hash", User.Role.ADMIN).isAdmin()).isTrue();
		assertThat(new User("member@sentinel.test", "구성원", "hash", User.Role.MEMBER).isAdmin()).isFalse();
	}

}
