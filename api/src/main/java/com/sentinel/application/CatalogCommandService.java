package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;

import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 삭제(보관). 행은 지우지 않고 보관 시각만 기록하며 하위 항목까지 연쇄 보관. 실행 이력(TestRun·TestResult)은 대상 아님.
 * <p>
 * 외래키가 없으므로 하위 항목 처리는 이 서비스 책임.
 * <ul>
 * <li>프로젝트: 환경, 스위트(케이스·스케줄 포함)</li>
 * <li>환경: 이 환경을 쓰는 스케줄</li>
 * <li>스위트: 케이스, 스케줄</li>
 * <li>케이스: 없음</li>
 * </ul>
 * 보관된 항목은 조회·실행 대상에서 제외. 미존재 또는 이미 보관된 항목은 {@link NotFoundException}.
 */
@Service
@Transactional
public class CatalogCommandService {

	private final ProjectRepository projects;

	private final EnvironmentRepository environments;

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	private final TestScheduleRepository schedules;

	private final Clock clock;

	CatalogCommandService(ProjectRepository projects, EnvironmentRepository environments, TestSuiteRepository suites,
			TestCaseRepository testCases, TestScheduleRepository schedules, Clock clock) {
		this.projects = projects;
		this.environments = environments;
		this.suites = suites;
		this.testCases = testCases;
		this.schedules = schedules;
		this.clock = clock;
	}

	public void archiveProject(long projectId) {
		Project project = projects.findByIdAndArchivedAtIsNull(projectId)
			.orElseThrow(() -> new NotFoundException("project", projectId));
		Instant now = clock.instant();
		environments.findByProjectIdAndArchivedAtIsNullOrderByName(projectId).forEach(environment -> archive(environment, now));
		suites.findByProjectIdAndArchivedAtIsNullOrderByName(projectId).forEach(suite -> archive(suite, now));
		project.archive(now);
	}

	public void archiveEnvironment(long environmentId) {
		archive(environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId)), clock.instant());
	}

	public void archiveSuite(long suiteId) {
		archive(suites.findByIdAndArchivedAtIsNull(suiteId)
			.orElseThrow(() -> new NotFoundException("test suite", suiteId)), clock.instant());
	}

	public void archiveCase(long caseId) {
		testCases.findByIdAndArchivedAtIsNull(caseId)
			.orElseThrow(() -> new NotFoundException("test case", caseId))
			.archive(clock.instant());
	}

	private void archive(Environment environment, Instant now) {
		schedules.findByEnvironmentIdAndArchivedAtIsNullOrderById(environment.getId())
			.forEach(schedule -> schedule.archive(now));
		environment.archive(now);
	}

	private void archive(TestSuite suite, Instant now) {
		testCases.findBySuiteIdAndArchivedAtIsNullOrderById(suite.getId()).forEach(testCase -> testCase.archive(now));
		// 프로젝트 연쇄 보관 시 환경 쪽에서 먼저 보관된 스케줄은 조회 시점 자동 flush로 제외됨
		schedules.findBySuiteIdAndArchivedAtIsNullOrderById(suite.getId()).forEach(schedule -> schedule.archive(now));
		suite.archive(now);
	}

}
