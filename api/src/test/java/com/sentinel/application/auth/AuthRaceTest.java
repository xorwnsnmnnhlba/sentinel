package com.sentinel.application.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.application.NotFoundException;
import com.sentinel.domain.User;
import com.sentinel.domain.UserSession;
import com.sentinel.domain.UserToken;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.UserSessionRepository;
import com.sentinel.infra.persistence.UserTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 로그인 세션·링크 토큰을 읽은 뒤 쓰기 전에 다른 요청(정리 배치, 초대 취소, 같은 링크의 다른 사용)이 먼저 바꾼 경우
 * (2026-10-16 변경분 리뷰). 조회 직후 다른 스레드(다른 트랜잭션)에서 경쟁 작업을 끝내 순서를 고정
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.auth.session-validity=12h" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class,
		AuthRaceTest.ClockConfiguration.class })
class AuthRaceTest {

	private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

	private static final String PASSWORD = "admin-password-1234";

	@MockitoSpyBean
	private UserSessionRepository sessions;

	@MockitoSpyBean
	private UserTokenRepository tokens;

	@Autowired
	private AuthService authService;

	@Autowired
	private UserService userService;

	@Autowired
	private ExpiredCredentialCleanup cleanup;

	@Autowired
	private UserRepository users;

	@Autowired
	private MutableClock clock;

	private User admin;

	@BeforeEach
	void createAdmin() {
		clock.set(START);
		admin = users.save(new User("admin-" + UUID.randomUUID() + "@sentinel.test", "관리자",
				TestAuthConfiguration.passwordHash(PASSWORD), User.Role.ADMIN));
	}

	@AfterEach
	void cleanUp() {
		tokens.deleteAll();
		sessions.deleteAll();
		users.deleteAll();
	}

	@Test
	void anExpiredSessionDeletedByTheCleanupMeanwhileIsJustNotAuthenticated() {
		String token = authService.login(admin.getEmail(), PASSWORD).token();
		clock.advance(Duration.ofHours(12));
		// 인증이 만료된 세션을 읽은 직후 새벽 정리 배치가 먼저 삭제
		doAnswer(invocation -> {
			Object found = real(sessions).answer(invocation);
			CompletableFuture.runAsync(cleanup::deleteExpired).join();
			return found;
		}).when(sessions).findByTokenHash(anyString());

		assertThat(authService.authenticate(token)).isEmpty();
		assertThat(sessions.count()).isZero();
	}

	@Test
	void aSessionOfADeletedUserRemovedMeanwhileIsJustNotAuthenticated() {
		String token = authService.login(admin.getEmail(), PASSWORD).token();
		admin.archive(clock.instant());
		users.save(admin);
		// 삭제된 사용자의 세션을 읽은 직후 다른 요청이 그 사용자의 세션을 모두 정리
		doAnswer(invocation -> {
			Object found = real(sessions).answer(invocation);
			CompletableFuture.runAsync(() -> authService.logoutEverywhere(admin.getId())).join();
			return found;
		}).when(sessions).findByTokenHash(anyString());

		assertThat(authService.authenticate(token)).isEmpty();
	}

	@Test
	void acceptingAnInviteRevokedMeanwhileFailsLikeAnyUnusableLink() {
		String email = "member-" + UUID.randomUUID() + "@sentinel.test";
		String token = userService.invite(email, User.Role.MEMBER, admin.getId()).token();
		long inviteId = tokens.findAll().getFirst().getId();
		// 수락이 초대를 읽은 직후 관리자가 취소
		doAnswer(invocation -> {
			Object found = real(tokens).answer(invocation);
			CompletableFuture.runAsync(() -> userService.revokeInvite(inviteId, admin.getId())).join();
			return found;
		}).when(tokens).findByTokenHash(anyString());

		assertThatThrownBy(() -> userService.acceptInvite(token, "구성원", "member-password"))
			.isInstanceOf(NotFoundException.class);
		assertThat(users.findByEmailAndArchivedAtIsNull(email)).isEmpty();
	}

	@Test
	void aPasswordResetLinkUsedTwiceAtOnceWorksOnlyOnce() {
		String token = userService.createPasswordReset(admin.getId(), admin.getId()).token();
		AtomicBoolean raced = new AtomicBoolean();
		// 첫 요청이 링크를 읽은 직후 같은 링크로 다른 요청이 먼저 비밀번호를 바꿈
		doAnswer(invocation -> {
			Object found = real(tokens).answer(invocation);
			if (raced.compareAndSet(false, true)) {
				CompletableFuture.runAsync(() -> userService.resetPassword(admin.getId(), token, "second-password-1"))
					.join();
			}
			return found;
		}).when(tokens).findByTokenHash(anyString());

		assertThatThrownBy(() -> userService.resetPassword(admin.getId(), token, "first-password-12"))
			.isInstanceOf(NotFoundException.class);
		// 먼저 끝난 요청의 비밀번호만 유효
		assertThat(authService.login(admin.getEmail(), "second-password-1").token()).isNotBlank();
		assertThat(tokens.findAll()).singleElement().satisfies(used -> assertThat(used.getUsedAt()).isNotNull());
	}

	@Test
	void keepsSessionAndTokenRowsTypesUnchanged() {
		// 위 경합 처리가 일반 경로(만료 세션 정리, 링크 1회 사용)를 바꾸지 않음
		String token = authService.login(admin.getEmail(), PASSWORD).token();
		clock.advance(Duration.ofHours(12));
		assertThat(authService.authenticate(token)).isEmpty();
		assertThat(sessions.findAll()).extracting(UserSession::getUserId).isEmpty();
		String reset = userService.createPasswordReset(admin.getId(), admin.getId()).token();
		userService.resetPassword(admin.getId(), reset, "brand-new-password");
		assertThat(tokens.findAll()).extracting(UserToken::getType).containsExactly(UserToken.Type.PASSWORD_RESET);
		assertThatThrownBy(() -> userService.resetPassword(admin.getId(), reset, "another-password-1"))
			.isInstanceOf(NotFoundException.class);
		assertThat(Optional.ofNullable(authService.login(admin.getEmail(), "brand-new-password").token())).isPresent();
	}

	/**
	 * 감시(spy)하는 리포지토리의 원래 동작. Spring은 인터페이스 프록시인 리포지토리를 위임 방식으로 감시하므로
	 * callRealMethod 대신 기본 응답(원래 리포지토리에 위임)을 호출
	 */
	private static Answer<?> real(Object spy) {
		return Mockito.mockingDetails(spy).getMockCreationSettings().getDefaultAnswer();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(START);
		}

	}

}
