package com.sentinel.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import com.sentinel.infra.persistence.TestSuiteQueries;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.extract.Extractor;
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

	/** 케이스 검색 결과 최대 수. 넘으면 검색어를 구체적으로 */
	public static final int MAX_SEARCH_RESULTS = 100;

	static final int MAX_QUERY_LENGTH = 200;

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
		Preload.of(projects, found.stream().map(environment -> environment.getProject().getId()));
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
		List<TestSuiteQueries.SuiteCaseCount> counts = projectId == null ? suites.countCases()
				: suites.countCasesByProjectId(projectId);
		Map<Long, Long> caseCounts = counts.stream()
			.collect(Collectors.toMap(TestSuiteQueries.SuiteCaseCount::suiteId,
					TestSuiteQueries.SuiteCaseCount::count));
		List<TestSuite> found = projectId == null ? suites.findByArchivedAtIsNullOrderByName()
				: suites.findByProjectIdAndArchivedAtIsNullOrderByName(projectId);
		Preload.of(projects, found.stream().map(suite -> suite.getProject().getId()));
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
		return listCases(suiteId, null);
	}

	/**
	 * @param suiteId null이면 전체
	 * @param query 지정 시 이름·경로 부분 일치 검색 (대소문자 무시, 앞뒤 공백 제거), 최대 {@value #MAX_SEARCH_RESULTS}건
	 * @throws InvalidRequestException 빈 검색어, {@value #MAX_QUERY_LENGTH}자 초과
	 */
	public List<CaseItem> listCases(Long suiteId, String query) {
		if (query == null) {
			List<TestCase> found = suiteId == null ? testCases.findAllActiveInRunOrder()
					: testCases.findActiveInRunOrder(suiteId);
			return casesWithSuites(found);
		}
		String trimmed = query.strip();
		if (trimmed.isEmpty() || trimmed.length() > MAX_QUERY_LENGTH) {
			throw new InvalidRequestException("q must be 1-" + MAX_QUERY_LENGTH + " characters");
		}
		return casesWithSuites(testCases.search(likePattern(trimmed), suiteId, MAX_SEARCH_RESULTS));
	}

	private List<CaseItem> casesWithSuites(List<TestCase> found) {
		Preload.of(suites, found.stream().map(testCase -> testCase.getSuite().getId()));
		return found.stream().map(CaseItem::of).toList();
	}


	/** 검색어를 문자 그대로 찾도록 LIKE 특수 문자 이스케이프 */
	private static String likePattern(String query) {
		String escaped = query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

	public CaseItem getCase(long caseId) {
		return CaseItem.of(testCases.findByIdAndArchivedAtIsNull(caseId).orElseThrow(() -> new NotFoundException("test case", caseId)));
	}

	/**
	 * @param webhookConfigured 실패 알림을 프로젝트 Webhook으로 보냄. 주소는 자격증명이라 응답에 넣지 않음
	 */
	public record ProjectItem(long id, String name, String description, boolean webhookConfigured) {

		static ProjectItem of(Project project) {
			return new ProjectItem(project.getId(), project.getName(), project.getDescription(), project.hasWebhook());
		}

	}

	/**
	 * @param variables 비밀이 아닌 값만 저장하므로 값 포함
	 * @param secretNames 시크릿 이름만 노출. 값은 조회 불가
	 */
	public record EnvironmentItem(long id, IdName project, String name, String baseUrl, Map<String, String> variables,
			List<String> secretNames) {

		/** 엔티티의 변수 목록은 실시간 뷰이므로 복사 (수정 전후 비교가 같은 목록을 보지 않도록). 입력 순서 유지 */
		public EnvironmentItem {
			variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
			secretNames = List.copyOf(secretNames);
		}

		static EnvironmentItem of(Environment environment) {
			return new EnvironmentItem(environment.getId(), IdName.of(environment.getProject()), environment.getName(),
					environment.getBaseUrl(), environment.getVariables(), environment.getSecretNames());
		}

	}

	public record SuiteItem(long id, IdName project, String name, String description, int caseCount) {

		static SuiteItem of(TestSuite suite, int caseCount) {
			return new SuiteItem(suite.getId(), IdName.of(suite.getProject()), suite.getName(), suite.getDescription(),
					caseCount);
		}

	}

	/**
	 * @param assertions 비어 있으면 기본 판정(status in [2xx]) 적용
	 * @param extractors 응답 값 추출 규칙
	 */
	public record CaseItem(long id, IdName suite, String name, Request request, List<Assertion> assertions,
			List<Extractor> extractors) {

		static CaseItem of(TestCase testCase) {
			RequestSpec spec = testCase.getRequest();
			return new CaseItem(testCase.getId(), IdName.of(testCase.getSuite()), testCase.getName(),
					new Request(spec.getMethod(), spec.getPath(), spec.getHeaders(), spec.getBody()),
					testCase.getAssertions(), testCase.getExtractors());
		}

	}

	public record Request(HttpMethod method, String path, Map<String, List<String>> headers, String body) {
	}

}
