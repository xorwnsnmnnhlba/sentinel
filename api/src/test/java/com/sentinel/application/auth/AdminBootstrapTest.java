package com.sentinel.application.auth;

import java.time.Duration;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 최초 관리자 생성. 테스트 컨텍스트마다 새 데이터베이스라 기동 시점에 사용자가 없으므로 실제 기동 경로(ApplicationReadyEvent)로 확인
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.auth.admin-email=" + AdminBootstrapTest.EMAIL,
		"sentinel.auth.admin-password=" + AdminBootstrapTest.PASSWORD })
@Import(TestcontainersConfiguration.class)
class AdminBootstrapTest {

	static final String EMAIL = "first-admin@sentinel.test";

	static final String PASSWORD = "first-admin-password";

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthService authService;

	@Test
	void createsTheConfiguredAdministratorOnStartupWhenNoUserExists() {
		User admin = users.findByEmailAndArchivedAtIsNull(EMAIL).orElseThrow();

		assertThat(admin.getRole()).isEqualTo(User.Role.ADMIN);
		assertThat(admin.getName()).isEqualTo("관리자");
		// 비밀번호는 해시로만 저장하고, 그 비밀번호로 로그인 가능
		assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
		assertThat(authService.login(EMAIL, PASSWORD).user().role()).isEqualTo(User.Role.ADMIN);
	}

	@Test
	void createsNothingOnLaterStartsOnceAnyUserExists() {
		long before = users.countByArchivedAtIsNull();
		AdminBootstrap laterStart = new AdminBootstrap(users, authService,
				properties("another-admin@sentinel.test", "another-admin-password"));

		laterStart.createAdminIfNoUsers();

		assertThat(users.countByArchivedAtIsNull()).isEqualTo(before);
		assertThat(users.findByEmailAndArchivedAtIsNull("another-admin@sentinel.test")).isEmpty();
	}

	@Test
	void createsNothingWithoutBothAnEmailAndAPassword() {
		UserRepository empty = mock(UserRepository.class);
		when(empty.countByArchivedAtIsNull()).thenReturn(0L);

		new AdminBootstrap(empty, authService, properties(null, null)).createAdminIfNoUsers();
		new AdminBootstrap(empty, authService, properties("admin@sentinel.test", " ")).createAdminIfNoUsers();
		new AdminBootstrap(empty, authService, properties(" ", "admin-password")).createAdminIfNoUsers();

		verify(empty, never()).save(any());
	}

	private static AuthProperties properties(String email, String password) {
		return new AuthProperties(Duration.ofHours(12), email, password, Duration.ofDays(7), Duration.ofHours(2));
	}

}
