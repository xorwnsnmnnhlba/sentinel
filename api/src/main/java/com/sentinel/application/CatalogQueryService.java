package com.sentinel.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.RequestSpec;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.Assertion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트·환경·스위트·케이스 조회.
 * <p>
 * 목록의 부모 ID는 필터 조건. 존재하지 않는 부모로 필터링하면 빈 목록 반환, 단건 조회만 미존재 시 {@link NotFoundException}.
 * 보관(소프트 삭제)된 항목은 목록에서 제외하고 단건 조회도 미존재로 취급.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CatalogQueryService {

	private final ProjectRepository projects;

	private final EnvironmentRepository environments;

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	public List<ProjectItem> listProjects() {
		return projects.findByArchivedAtIsNullOrderByName().stream().map(ProjectItem::of).toList();
	}

	public ProjectItem getProject(long projectId) {
		return ProjectItem.of(projects.findByIdAndArchivedAtIsNull(projectId).orElseThrow(() -> new NotFoundException("project", projectId)));
	}

	/**
	 * @param projectId null이면 전체
	 */
	public List<EnvironmentItem> listEnvironments(Long projectId) {
		List<Environment> found = projectId == null ? environments.findByArchivedAtIsNullOrderByName()
				: environments.findByProjectIdAndArchivedAtIsNullOrderByName(projectId);
		return found.stream().map(EnvironmentItem::of).toList();
	}

	public EnvironmentItem getEnvironment(long environmentId) {
		return EnvironmentItem.of(environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId)));
	}

	/**
	 * @param projectId null이면 전체
	 */
	public List<SuiteItem> listSuites(Long projectId) {
		List<TestSuiteRepository.SuiteCaseCount> counts = projectId == null ? suites.countCases()
				: suites.countCasesByProjectId(projectId);
		Map<Long, Long> caseCounts = counts.stream()
			.collect(Collectors.toMap(TestSuiteRepository.SuiteCaseCount::getSuiteId,
					TestSuiteRepository.SuiteCaseCount::getCount));
		List<TestSuite> found = projectId == null ? suites.findByArchivedAtIsNullOrderByName()
				: suites.findByProjectIdAndArchivedAtIsNullOrderByName(projectId);
		return found.stream()
			.map(suite -> SuiteItem.of(suite, caseCounts.getOrDefault(suite.getId(), 0L).intValue()))
			.toList();
	}

	public SuiteItem getSuite(long suiteId) {
		TestSuite suite = suites.findByIdAndArchivedAtIsNull(suiteId).orElseThrow(() -> new NotFoundException("test suite", suiteId));
		return SuiteItem.of(suite, (int) testCases.countBySuiteIdAndArchivedAtIsNull(suiteId));
	}

	/**
	 * @param suiteId null이면 전체
	 */
	public List<CaseItem> listCases(Long suiteId) {
		List<TestCase> found = suiteId == null ? testCases.findByArchivedAtIsNullOrderById()
				: testCases.findBySuiteIdAndArchivedAtIsNullOrderById(suiteId);
		return found.stream().map(CaseItem::of).toList();
	}

	public CaseItem getCase(long caseId) {
		return CaseItem.of(testCases.findByIdAndArchivedAtIsNull(caseId).orElseThrow(() -> new NotFoundException("test case", caseId)));
	}

	public record ProjectItem(long id, String name, String description) {

		static ProjectItem of(Project project) {
			return new ProjectItem(project.getId(), project.getName(), project.getDescription());
		}

	}

	/**
	 * @param variables 비밀 값이 아닌 변수만 저장하는 원칙(시크릿 분리 전)이므로 값 포함
	 */
	public record EnvironmentItem(long id, long projectId, String name, String baseUrl, Map<String, String> variables) {

		static EnvironmentItem of(Environment environment) {
			return new EnvironmentItem(environment.getId(), environment.getProject().getId(), environment.getName(),
					environment.getBaseUrl(), environment.getVariables());
		}

	}

	public record SuiteItem(long id, long projectId, String name, String description, int caseCount) {

		static SuiteItem of(TestSuite suite, int caseCount) {
			return new SuiteItem(suite.getId(), suite.getProject().getId(), suite.getName(), suite.getDescription(),
					caseCount);
		}

	}

	/**
	 * @param assertions 비어 있으면 기본 판정(status in [2xx]) 적용
	 */
	public record CaseItem(long id, long suiteId, String name, Request request, List<Assertion> assertions) {

		static CaseItem of(TestCase testCase) {
			RequestSpec spec = testCase.getRequest();
			return new CaseItem(testCase.getId(), testCase.getSuite().getId(), testCase.getName(),
					new Request(spec.getMethod(), spec.getPath(), spec.getHeaders(), spec.getBody()),
					testCase.getAssertions());
		}

	}

	public record Request(HttpMethod method, String path, Map<String, List<String>> headers, String body) {
	}

}
