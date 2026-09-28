package com.sentinel.application;

import java.util.List;
import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestScheduleRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 소프트 삭제: DELETE API, 연쇄 보관, 조회·실행 제외, 실행 이력 유지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class,
		InlineBackgroundRunsConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class CatalogArchiveTest {

	/** 변경 이력에 남길 사용자. 이력 자체는 이 테스트의 대상이 아님 */
	private static final long ACTOR = 1L;

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private TestScheduleRepository schedules;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		testerId = null;
		executor.reset();
	}

	@Test
	void deletedCaseDisappearsButItsRunHistoryRemains() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		TestCase removed = fixtures.testCase(suite, HttpMethod.GET, "/removed", null, null);
		TestCase kept = fixtures.testCase(suite, HttpMethod.GET, "/kept", null, null);
		long earlierRun = testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());

		assertThat(mvc.delete().uri("/api/cases/{id}", removed.getId())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri("/api/cases/{id}", removed.getId())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.delete().uri("/api/cases/{id}", removed.getId())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/cases?suiteId={id}", suite.getId())).bodyJson()
			.extractingPath("$[*].id")
			.asArray()
			.containsExactly(kept.getId().intValue());
		assertThat(mvc.get().uri("/api/suites/{id}", suite.getId())).bodyJson().extractingPath("$.caseCount").isEqualTo(1);

		// 이력은 보관된 케이스 이름까지 그대로 조회
		assertThat(testRunService.getRun(earlierRun).results()).extracting(TestRunDetail.Result::testCaseName)
			.contains(removed.getName());
		// 이후 실행 대상에서 제외
		executor.reset();
		testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());
		assertThat(executor.requests()).extracting(request -> request.uri().getPath()).containsExactly("/kept");
		assertThatThrownBy(() -> testRunService.runTestCase(removed.getId(), environment.getId(), tester()))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void deletingProjectArchivesEverythingBelowIt() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		TestCase testCase = fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * 1-5", ACTOR);
		long run = testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.MANUAL, tester());

		assertThat(mvc.delete().uri("/api/projects/{id}", project.getId())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri("/api/projects/{id}", project.getId())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/environments/{id}", environment.getId())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/suites/{id}", suite.getId())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/cases/{id}", testCase.getId())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/schedules/{id}", schedule.id())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/projects")).bodyJson().extractingPath("$.length()").isEqualTo(0);

		var archivedSchedule = schedules.findById(schedule.id()).orElseThrow();
		assertThat(archivedSchedule.isArchived()).isTrue();
		assertThat(archivedSchedule.isEnabled()).isFalse();
		assertThat(archivedSchedule.getNextRunAt()).isNull();

		assertThat(mvc.get().uri("/api/runs/{id}", run)).hasStatusOk().bodyJson().extractingPath("$.suiteName")
			.isEqualTo(suite.getName());
		assertThat(mvc.get().uri("/api/runs")).bodyJson().extractingPath("$.totalElements").isEqualTo(1);
	}

	@Test
	void deletingEnvironmentArchivesOnlyItsSchedules() {
		Project project = fixtures.project();
		Environment dev = fixtures.environment(project, Map.of());
		Environment staging = fixtures.environment(fixtures.project(), Map.of());
		TestSuite suite = fixtures.suite(project);
		ScheduleDetail devSchedule = scheduleService.create(suite.getId(), dev.getId(), "10 9 * * 1-5", ACTOR);

		assertThat(mvc.delete().uri("/api/environments/{id}", dev.getId())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri("/api/schedules/{id}", devSchedule.id())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/suites/{id}", suite.getId())).hasStatusOk();
		assertThat(mvc.get().uri("/api/environments/{id}", staging.getId())).hasStatusOk();
		assertThatThrownBy(() -> testRunService.runSuite(suite.getId(), dev.getId(), TestRun.Trigger.MANUAL, tester()))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void deletingSuiteArchivesCasesAndSchedules() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		TestCase testCase = fixtures.testCase(suite, HttpMethod.GET, "/a", null, null);
		ScheduleDetail schedule = scheduleService.create(suite.getId(), environment.getId(), "10 9 * * 1-5", ACTOR);

		assertThat(mvc.delete().uri("/api/suites/{id}", suite.getId())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri("/api/cases/{id}", testCase.getId())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/schedules?suiteId={id}", suite.getId())).bodyJson().extractingPath("$.length()")
			.isEqualTo(0);
		assertThat(schedules.findById(schedule.id()).orElseThrow().isEnabled()).isFalse();
		assertThat(mvc.get().uri("/api/environments/{id}", environment.getId())).hasStatusOk();
	}

	@Test
	void deletedScheduleCannotBeReEnabledOrDeletedAgain() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		ScheduleDetail schedule = scheduleService.create(fixtures.suite(project).getId(), environment.getId(),
				"10 9 * * 1-5", ACTOR);

		assertThat(mvc.delete().uri("/api/schedules/{id}", schedule.id())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.delete().uri("/api/schedules/{id}", schedule.id())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.patch()
			.uri("/api/schedules/{id}", schedule.id())
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"enabled\": true}")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(schedules.findById(schedule.id()).orElseThrow().isEnabled()).isFalse();
		assertThat(mvc.get().uri("/api/schedules")).bodyJson().extractingPath("$.length()").isEqualTo(0);
	}

	@Test
	void keepsUnrelatedProjectsUntouched() {
		Project removed = fixtures.project();
		Project other = fixtures.project();
		TestSuite otherSuite = fixtures.suite(other);
		fixtures.testCase(otherSuite, HttpMethod.GET, "/a", null, null);

		mvc.delete().uri("/api/projects/{id}", removed.getId()).exchange();

		assertThat(mvc.get().uri("/api/projects")).bodyJson().extractingPath("$[*].id").asArray()
			.containsExactly(other.getId().intValue());
		assertThat(mvc.get().uri("/api/suites/{id}", otherSuite.getId())).hasStatusOk().bodyJson()
			.extractingPath("$.caseCount")
			.isEqualTo(1);
	}


	/** 수동 실행 주체. 실행마다 같은 계정을 사용하도록 한 번만 생성 */
	private long tester() {
		if (testerId == null) {
			testerId = fixtures.user().getId();
		}
		return testerId;
	}

	private Long testerId;

}
