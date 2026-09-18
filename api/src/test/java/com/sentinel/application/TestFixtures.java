package com.sentinel.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.RequestSpec;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * 실행 흐름 테스트용 데이터 생성·정리. 스프링 컨텍스트 공유 시 이름 충돌을 피하기 위해 무작위 접미사 사용.
 */
@Component
public class TestFixtures {

	private final ProjectRepository projects;

	private final EnvironmentRepository environments;

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	private final TestRunRepository runs;

	private final TestResultRepository results;

	TestFixtures(ProjectRepository projects, EnvironmentRepository environments, TestSuiteRepository suites,
			TestCaseRepository testCases, TestRunRepository runs, TestResultRepository results) {
		this.projects = projects;
		this.environments = environments;
		this.suites = suites;
		this.testCases = testCases;
		this.runs = runs;
		this.results = results;
	}

	public Project project() {
		return projects.save(new Project("project-" + UUID.randomUUID(), null));
	}

	public Environment environment(Project project, Map<String, String> variables) {
		Environment environment = new Environment(project, "dev", "https://gw.dev.internal");
		variables.forEach(environment::putVariable);
		return environments.save(environment);
	}

	public TestCase testCase(Project project, HttpMethod method, String path, Map<String, List<String>> headers,
			String body) {
		TestSuite suite = suites.save(new TestSuite(project, "suite-" + UUID.randomUUID(), null));
		return testCases.save(new TestCase(suite, "case", new RequestSpec(method, path, headers, body)));
	}

	public void deleteAll() {
		results.deleteAllInBatch();
		runs.deleteAllInBatch();
		testCases.deleteAllInBatch();
		suites.deleteAllInBatch();
		environments.deleteAllInBatch();
		projects.deleteAllInBatch();
	}

}
