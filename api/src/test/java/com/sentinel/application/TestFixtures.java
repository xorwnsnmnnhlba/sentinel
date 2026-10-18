package com.sentinel.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.RequestSpec;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.ChangeLogRepository;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.HolidayRepository;
import com.sentinel.infra.persistence.MaintenanceWindowRepository;
import com.sentinel.infra.persistence.NotificationDeliveryRepository;
import com.sentinel.infra.persistence.MonitoringGapRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.SlowdownAlertRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.infra.persistence.UserRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.extract.Extractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 실행 흐름 테스트용 데이터 생성·정리. 스프링 컨텍스트 공유 시 이름 충돌을 피하기 위해 무작위 접미사 사용.
 */
@Component
@RequiredArgsConstructor
public class TestFixtures {

	private final ProjectRepository projects;

	private final EnvironmentRepository environments;

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final TestScheduleRepository schedules;

	private final UserRepository users;

	private final ChangeLogRepository changeLogs;

	private final MaintenanceWindowRepository maintenanceWindows;

	private final MonitoringGapRepository monitoringGaps;

	private final SlowdownAlertRepository slowdownAlerts;

	private final HolidayRepository holidays;

	private final NotificationDeliveryRepository notificationDeliveries;

	private final TestRunService testRunService;

	/** 수동 실행 주체 등 사용자 필요한 테스트용 계정 */
	public User user() {
		return users.save(new User("tester-" + UUID.randomUUID() + "@sentinel.test", "테스터", "$2a$10$"
				+ "ijklmnopqrstuvwxyzABCDE.FGHIJKLMNOPQRSTUVWXYZabcdefghi", User.Role.ADMIN));
	}

	public Project project() {
		return projects.save(new Project("project-" + UUID.randomUUID(), null));
	}

	public Environment environment(Project project, Map<String, String> variables) {
		return environment(project, "dev", variables);
	}

	/** 한 프로젝트에 환경을 여러 개 만들 때는 이름 지정 (프로젝트 내 이름 유니크) */
	public Environment environment(Project project, String name, Map<String, String> variables) {
		Environment environment = new Environment(project, name, "https://gw.dev.internal");
		environment.replaceVariables(variables);
		return environments.save(environment);
	}

	public TestSuite suite(Project project) {
		return suites.save(new TestSuite(project, "suite-" + UUID.randomUUID(), null));
	}

	public TestCase testCase(Project project, HttpMethod method, String path, Map<String, List<String>> headers,
			String body) {
		return testCase(suite(project), method, path, headers, body);
	}

	public TestCase testCase(TestSuite suite, HttpMethod method, String path, Map<String, List<String>> headers,
			String body) {
		return testCase(suite, method, path, headers, body, List.of());
	}

	public TestCase testCase(TestSuite suite, HttpMethod method, String path, Map<String, List<String>> headers,
			String body, List<Assertion> assertions) {
		return testCase(suite, method, path, headers, body, assertions, List.of());
	}

	public TestCase testCase(TestSuite suite, HttpMethod method, String path, Map<String, List<String>> headers,
			String body, List<Assertion> assertions, List<Extractor> extractors) {
		return testCases.save(new TestCase(suite, "case-" + UUID.randomUUID(),
				new RequestSpec(method, path, headers, body), assertions, extractors));
	}

	/** 스위트 전체 실행 (실행 끝까지 대기). 다른 패키지 테스트의 데이터 준비용 */
	public long runSuite(long suiteId, long environmentId, TestRun.Trigger trigger, Long triggeredBy) {
		return testRunService.runSuite(suiteId, environmentId, trigger, triggeredBy);
	}

	/** 사용자는 삭제하지 않음. 기본 로그인 계정(TestAuthConfiguration)이 함께 지워지면 이후 요청이 401 */
	public void deleteAll() {
		changeLogs.deleteAllInBatch();
		maintenanceWindows.deleteAllInBatch();
		monitoringGaps.deleteAllInBatch();
		slowdownAlerts.deleteAllInBatch();
		holidays.deleteAllInBatch();
		notificationDeliveries.deleteAllInBatch();
		schedules.deleteAllInBatch();
		results.deleteAllInBatch();
		runs.deleteAllInBatch();
		testCases.deleteAllInBatch();
		suites.deleteAllInBatch();
		environments.deleteAllInBatch();
		projects.deleteAllInBatch();
	}

}
