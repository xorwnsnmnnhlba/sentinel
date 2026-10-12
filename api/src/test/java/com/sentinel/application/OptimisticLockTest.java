package com.sentinel.application;

import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSchedule;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동시 수정 덮어쓰기 방지 (낙관적 잠금, V30). 먼저 읽은 내용으로 나중에 저장하면 그사이의 다른 변경을 덮어쓰지 않고 실패
 * (2026-10-12 독립 리뷰: 폴러의 다음 실행 시각 갱신이 사용자의 끄기·삭제를 되돌림, 시크릿 동시 수정 중 하나가 사라짐)
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class OptimisticLockTest {

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private TestScheduleRepository schedules;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private TestCaseRepository testCases;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void refusesToWriteAScheduleReadBeforeAnotherChange() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		long scheduleId = scheduleService.create(suite.getId(), environment.getId(), "*/10 * * * *", 1L).id();
		// 먼저 읽어 둔 스케줄 (폴러가 다음 실행 시각을 옮기려는 중인 것과 같은 상황)
		TestSchedule claimed = schedules.findById(scheduleId).orElseThrow();

		// 그사이 사용자가 끔
		scheduleService.update(scheduleId, false, null, null, 1L);

		claimed.changeSkipHolidays(true);
		assertThatThrownBy(() -> schedules.save(claimed)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
		assertThat(schedules.findById(scheduleId).orElseThrow().isEnabled()).isFalse();
	}

	@Test
	void refusesToWriteAnEnvironmentReadBeforeAnotherChange() {
		Environment environment = fixtures.environment(fixtures.project(), Map.of());
		Environment first = environments.findById(environment.getId()).orElseThrow();
		Environment second = environments.findById(environment.getId()).orElseThrow();

		first.putSecret("token", "v1:first");
		environments.save(first);

		second.putSecret("apiKey", "v1:second");
		assertThatThrownBy(() -> environments.save(second)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
		assertThat(environments.findById(environment.getId()).orElseThrow().getSecretNames()).containsExactly("token");
	}

	@Test
	void refusesToWriteACaseReadBeforeAnotherChange() {
		TestCase testCase = fixtures.testCase(fixtures.project(), HttpMethod.GET, "/health", null, null);
		TestCase first = testCases.findById(testCase.getId()).orElseThrow();
		TestCase second = testCases.findById(testCase.getId()).orElseThrow();

		first.rename("첫 번째 수정");
		testCases.save(first);

		second.rename("두 번째 수정");
		assertThatThrownBy(() -> testCases.save(second)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
		assertThat(testCases.findById(testCase.getId()).orElseThrow().getName()).isEqualTo("첫 번째 수정");
	}

}
