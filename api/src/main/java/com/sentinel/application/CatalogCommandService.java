package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import com.sentinel.application.CatalogQueryService.CaseItem;
import com.sentinel.application.CatalogQueryService.EnvironmentItem;
import com.sentinel.application.CatalogQueryService.ProjectItem;
import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.application.CatalogQueryService.SuiteItem;
import com.sentinel.domain.ArchivableEntity;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.RequestSpec;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestScheduleRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.assertion.Assertion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트·환경·스위트·케이스 등록, 수정, 삭제(보관).
 * <p>
 * 등록·수정
 * <ul>
 * <li>부모(프로젝트·스위트)가 미존재 또는 보관 상태면 {@link NotFoundException}. 부모 변경(이동)은 미지원</li>
 * <li>같은 부모 아래 사용 중인 이름과 중복되면 {@link ConflictException}. 보관된 항목의 이름은 재사용 가능</li>
 * <li>잘못된 값(빈 이름, 길이 초과, 잘못된 baseUrl·경로)은 {@link InvalidRequestException}</li>
 * <li>수정은 부분 수정. null 필드는 유지하고, 지정한 변수·요청 정의·판정 규칙은 전체 교체</li>
 * </ul>
 * 삭제(보관): 행은 지우지 않고 보관 시각만 기록하며 하위 항목까지 연쇄 보관. 실행 이력(TestRun·TestResult)은 대상 아님.
 * 외래키가 없으므로 하위 항목 처리는 이 서비스 책임.
 * <ul>
 * <li>프로젝트: 환경, 스위트(케이스·스케줄 포함)</li>
 * <li>환경: 이 환경을 쓰는 스케줄</li>
 * <li>스위트: 케이스, 스케줄</li>
 * <li>케이스: 없음</li>
 * </ul>
 * 보관된 항목은 조회·수정·실행 대상에서 제외. 미존재 또는 이미 보관된 항목은 {@link NotFoundException}.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CatalogCommandService {

	private final ProjectRepository projects;

	private final EnvironmentRepository environments;

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	private final TestScheduleRepository schedules;

	private final Clock clock;

	public ProjectItem createProject(String name, String description) {
		requireUnusedName("project", name, null, projects::findByNameAndArchivedAtIsNull);
		return ProjectItem.of(projects.save(valid(() -> new Project(name, description))));
	}

	public ProjectItem updateProject(long projectId, String name, String description) {
		Project project = findProject(projectId);
		if (name != null) {
			requireUnusedName("project", name, projectId, projects::findByNameAndArchivedAtIsNull);
			change(() -> project.rename(name));
		}
		if (description != null) {
			change(() -> project.describe(description));
		}
		return ProjectItem.of(project);
	}

	/**
	 * @param variables null이면 변수 없음
	 */
	public EnvironmentItem createEnvironment(long projectId, String name, String baseUrl,
			Map<String, String> variables) {
		Project project = findProject(projectId);
		requireUnusedName("environment", name, null,
				unique -> environments.findByProjectIdAndNameAndArchivedAtIsNull(projectId, unique));
		Environment environment = valid(() -> {
			Environment created = new Environment(project, name, baseUrl);
			created.replaceVariables(variables);
			return created;
		});
		return EnvironmentItem.of(environments.save(environment));
	}

	/**
	 * @param variables 지정 시 전체 교체
	 */
	public EnvironmentItem updateEnvironment(long environmentId, String name, String baseUrl,
			Map<String, String> variables) {
		Environment environment = findEnvironment(environmentId);
		long projectId = environment.getProject().getId();
		if (name != null) {
			requireUnusedName("environment", name, environmentId,
					unique -> environments.findByProjectIdAndNameAndArchivedAtIsNull(projectId, unique));
			change(() -> environment.rename(name));
		}
		if (baseUrl != null) {
			change(() -> environment.changeBaseUrl(baseUrl));
		}
		if (variables != null) {
			change(() -> environment.replaceVariables(variables));
		}
		return EnvironmentItem.of(environment);
	}

	public SuiteItem createSuite(long projectId, String name, String description) {
		Project project = findProject(projectId);
		requireUnusedName("test suite", name, null,
				unique -> suites.findByProjectIdAndNameAndArchivedAtIsNull(projectId, unique));
		return SuiteItem.of(suites.save(valid(() -> new TestSuite(project, name, description))), 0);
	}

	public SuiteItem updateSuite(long suiteId, String name, String description) {
		TestSuite suite = findSuite(suiteId);
		long projectId = suite.getProject().getId();
		if (name != null) {
			requireUnusedName("test suite", name, suiteId,
					unique -> suites.findByProjectIdAndNameAndArchivedAtIsNull(projectId, unique));
			change(() -> suite.rename(name));
		}
		if (description != null) {
			change(() -> suite.describe(description));
		}
		return SuiteItem.of(suite, (int) testCases.countBySuiteIdAndArchivedAtIsNull(suiteId));
	}

	/**
	 * @param assertions null 또는 빈 목록이면 기본 판정(status in [2xx])
	 */
	public CaseItem createCase(long suiteId, String name, Request request, List<Assertion> assertions) {
		TestSuite suite = findSuite(suiteId);
		requireUnusedName("test case", name, null,
				unique -> testCases.findBySuiteIdAndNameAndArchivedAtIsNull(suiteId, unique));
		return CaseItem.of(testCases.save(valid(() -> new TestCase(suite, name, specOf(request), assertions))));
	}

	/**
	 * @param request 지정 시 요청 정의 전체 교체
	 * @param assertions 지정 시 전체 교체. 빈 목록이면 기본 판정으로 복귀
	 */
	public CaseItem updateCase(long caseId, String name, Request request, List<Assertion> assertions) {
		TestCase testCase = testCases.findByIdAndArchivedAtIsNull(caseId)
			.orElseThrow(() -> new NotFoundException("test case", caseId));
		long suiteId = testCase.getSuite().getId();
		if (name != null) {
			requireUnusedName("test case", name, caseId,
					unique -> testCases.findBySuiteIdAndNameAndArchivedAtIsNull(suiteId, unique));
			change(() -> testCase.rename(name));
		}
		if (request != null) {
			change(() -> testCase.changeRequest(specOf(request)));
		}
		if (assertions != null) {
			change(() -> testCase.changeAssertions(assertions));
		}
		return CaseItem.of(testCase);
	}

	public void archiveProject(long projectId) {
		Project project = findProject(projectId);
		Instant now = clock.instant();
		environments.findByProjectIdAndArchivedAtIsNullOrderByName(projectId).forEach(environment -> archive(environment, now));
		suites.findByProjectIdAndArchivedAtIsNullOrderByName(projectId).forEach(suite -> archive(suite, now));
		project.archive(now);
	}

	public void archiveEnvironment(long environmentId) {
		archive(findEnvironment(environmentId), clock.instant());
	}

	public void archiveSuite(long suiteId) {
		archive(findSuite(suiteId), clock.instant());
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

	private Project findProject(long projectId) {
		return projects.findByIdAndArchivedAtIsNull(projectId)
			.orElseThrow(() -> new NotFoundException("project", projectId));
	}

	private Environment findEnvironment(long environmentId) {
		return environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
	}

	private TestSuite findSuite(long suiteId) {
		return suites.findByIdAndArchivedAtIsNull(suiteId)
			.orElseThrow(() -> new NotFoundException("test suite", suiteId));
	}

	/**
	 * 이름 중복 검사. 엔티티 변경 전에 호출해야 조회 전 자동 flush로 인한 유니크 제약 위반을 피함. 동시 등록 경합은 DB 유니크 제약이 차단
	 * @param selfId 수정 대상 자신은 제외. 등록 시 null
	 */
	private static void requireUnusedName(String resource, String name, Long selfId,
			Function<String, Optional<? extends ArchivableEntity>> finder) {
		if (name == null || name.isBlank()) {
			return;
		}
		String normalized = name.strip();
		finder.apply(normalized)
			.filter(found -> !found.getId().equals(selfId))
			.ifPresent(found -> {
				throw new ConflictException(resource + " name '" + normalized + "' is already in use");
			});
	}

	private static RequestSpec specOf(Request request) {
		if (request == null) {
			throw new IllegalArgumentException("request must not be null");
		}
		return new RequestSpec(request.method(), request.path(), request.headers(), request.body());
	}

	private static <T> T valid(Supplier<T> action) {
		try {
			return action.get();
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
	}

	private static void change(Runnable action) {
		valid(() -> {
			action.run();
			return null;
		});
	}

}
