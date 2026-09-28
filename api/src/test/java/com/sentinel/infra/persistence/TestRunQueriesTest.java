package com.sentinel.infra.persistence;

import java.time.Instant;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.BaseEntity;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** 실행 조회 중 서비스 테스트로 재현하기 어려운 경계 (같은 시각에 시작한 실행) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TestRunQueriesTest {

	private static final Instant T = Instant.parse("2026-09-28T00:00:00Z");

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private EnvironmentRepository environments;

	@Autowired
	private TestSuiteRepository suites;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private EntityManager entityManager;

	private TestSuite suite;

	private Environment environment;

	@BeforeEach
	void setUp() {
		Project project = projects.save(new Project("billing", null));
		environment = environments.save(new Environment(project, "dev", "https://gw"));
		suite = suites.save(new TestSuite(project, "charge", null));
	}

	@Test
	void ordersRunsStartedAtTheSameInstantById() {
		TestRun first = completedRunAt(T);
		TestRun second = completedRunAt(T);
		TestRun third = completedRunAt(T.plusSeconds(1));
		entityManager.clear();

		assertThat(previousOf(third)).isEqualTo(second.getId());
		// 시작 시각이 같으면 ID가 작은 실행이 앞선 실행
		assertThat(previousOf(second)).isEqualTo(first.getId());
		assertThat(previousOf(first)).isNull();
	}

	private TestRun completedRunAt(Instant startedAt) {
		TestRun run = runs.saveAndFlush(TestRun.start(suite, environment, TestRun.Trigger.SCHEDULED, startedAt));
		assertThat(runs.completeIfRunning(run.getId(), startedAt.plusMillis(1))).isEqualTo(1);
		return run;
	}

	private Long previousOf(TestRun run) {
		return runs
			.findPreviousWholeRuns(suite.getId(), environment.getId(), TestRun.Status.COMPLETED, run.getStartedAt(),
					run.getId(), 1)
			.stream()
			.map(BaseEntity::getId)
			.findFirst()
			.orElse(null);
	}

}
