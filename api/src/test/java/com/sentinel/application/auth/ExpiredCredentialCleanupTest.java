package com.sentinel.application.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.MutableClock;
import com.sentinel.domain.User;
import com.sentinel.domain.UserToken;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.infra.persistence.UserSessionRepository;
import com.sentinel.infra.persistence.UserTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료된 로그인 세션과 초대·재설정 링크 삭제. 만료 전 행과 동작(로그인 유지, 링크 사용)은 그대로
 */
@SpringBootTest(properties = { "sentinel.schedule.enabled=false", "sentinel.auth.session-validity=12h",
		"sentinel.auth.invite-validity=7d", "sentinel.auth.password-reset-validity=2h" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class,
		ExpiredCredentialCleanupTest.ClockConfiguration.class })
class ExpiredCredentialCleanupTest {

	private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

	private static final String PASSWORD = "member-password-1234";

	@Autowired
	private ExpiredCredentialCleanup cleanup;

	@Autowired
	private AuthService authService;

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository users;

	@Autowired
	private UserSessionRepository sessions;

	@Autowired
	private UserTokenRepository tokens;

	@Autowired
	private MutableClock clock;

	@Autowired
	private List<ScheduledTaskHolder> taskHolders;

	private User member;

	@BeforeEach
	void createMember() {
		clock.set(START);
		member = users.save(new User("member-" + UUID.randomUUID() + "@sentinel.test", "구성원",
				TestAuthConfiguration.passwordHash(PASSWORD), User.Role.ADMIN));
	}

	@AfterEach
	void cleanUp() {
		tokens.deleteAll();
		sessions.deleteAll();
		users.deleteAll();
	}

	@Test
	void deletesSessionsOnceTheyExpireAndKeepsTheOthersWorking() {
		String first = authService.login(member.getEmail(), PASSWORD).token();
		clock.advance(Duration.ofHours(3));
		String second = authService.login(member.getEmail(), PASSWORD).token();

		// 첫 세션의 만료 시각 직전: 둘 다 유지
		clock.set(START.plus(Duration.ofHours(12)).minusSeconds(1));
		assertThat(cleanup.deleteExpired()).isEqualTo(new ExpiredCredentialCleanup.Deleted(0, 0));
		assertThat(sessions.count()).isEqualTo(2);

		// 만료 시각 정각부터 만료 (인증과 같은 기준). 만료 뒤 다시 쓰지 않은 세션이 대상 (쓰면 인증에서 바로 삭제)
		clock.set(START.plus(Duration.ofHours(12)));
		assertThat(cleanup.deleteExpired()).isEqualTo(new ExpiredCredentialCleanup.Deleted(1, 0));

		assertThat(sessions.count()).isEqualTo(1);
		assertThat(authService.authenticate(first)).isEmpty();
		assertThat(authService.authenticate(second)).hasValueSatisfying(user -> assertThat(user.id()).isEqualTo(member.getId()));
	}

	@Test
	void deletesInviteAndResetLinksOnceTheyExpireWhetherUsedOrNot() {
		String used = userService.invite("used-" + UUID.randomUUID() + "@sentinel.test", User.Role.MEMBER, member.getId())
			.token();
		String pending = userService
			.invite("pending-" + UUID.randomUUID() + "@sentinel.test", User.Role.MEMBER, member.getId())
			.token();
		userService.createPasswordReset(member.getId(), member.getId());
		userService.acceptInvite(used, "가입", PASSWORD);

		// 재설정 링크(2시간)만 만료
		clock.advance(Duration.ofHours(2));
		assertThat(cleanup.deleteExpired().links()).isEqualTo(1);
		assertThat(tokens.findAll()).extracting(UserToken::getType).containsOnly(UserToken.Type.INVITE).hasSize(2);
		assertThat(userService.invite(pending).email()).startsWith("pending-");

		// 초대(7일)는 사용 여부와 무관하게 만료 후 삭제
		clock.set(START.plus(Duration.ofDays(7)));
		assertThat(cleanup.deleteExpired().links()).isEqualTo(2);
		assertThat(tokens.count()).isZero();
		// 계정은 그대로
		assertThat(users.findByEmailAndArchivedAtIsNull(member.getEmail())).isPresent();
	}

	@Test
	void runsWithTheRetentionBatchSchedule() {
		// 원문 정리·알림 이력 삭제와 같은 시각에 따로 등록 (한쪽이 실패해도 다른 쪽은 실행)
		assertThat(taskHolders.stream().flatMap(holder -> holder.getScheduledTasks().stream()))
			.map(task -> task.getTask())
			.filteredOn(CronTask.class::isInstance)
			.extracting(task -> ((CronTask) task).getExpression())
			.filteredOn("0 30 3 * * *"::equals)
			.hasSize(3);
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
