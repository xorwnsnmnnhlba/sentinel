package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Function;
import java.util.function.Supplier;

import com.sentinel.application.CatalogQueryService.CaseItem;
import com.sentinel.application.CatalogQueryService.EnvironmentItem;
import com.sentinel.application.CatalogQueryService.ProjectItem;
import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.application.CatalogQueryService.SuiteItem;
import com.sentinel.application.ChangeLogService.Changes;
import com.sentinel.application.ChangeLogService.Target;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
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
import com.sentinel.application.notification.WebhookUrls;
import com.sentinel.application.secret.SecretCipher;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.extract.Extractor;
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
 * <p>
 * 모든 등록·수정·삭제는 같은 트랜잭션에서 변경 이력({@link ChangeLogService})을 남김. 수정은 값이 실제로 바뀐 항목 이름만.
 * {@code actorId}는 변경한 사용자
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

	private final SecretCipher secretCipher;

	private final ChangeLogService changeLog;

	private final Clock clock;

	public ProjectItem createProject(String name, String description, long actorId) {
		requireUnusedName("project", name, null, projects::findByNameAndArchivedAtIsNull);
		Project project = projects.save(valid(() -> new Project(name, description)));
		changeLog.record(actorId, target(project), Action.CREATED);
		return ProjectItem.of(project);
	}

	/**
	 * @param webhookUrl 실패 알림 Webhook. 빈 문자열이면 삭제(서버 기본 채널), null이면 유지. 암호화하여 저장
	 * @throws InvalidRequestException 잘못된 Webhook 주소, 암호화 키 미설정
	 */
	public ProjectItem updateProject(long projectId, String name, String description, String webhookUrl,
			long actorId) {
		Project project = findProject(projectId);
		ProjectItem before = ProjectItem.of(project);
		if (name != null) {
			requireUnusedName("project", name, projectId, projects::findByNameAndArchivedAtIsNull);
			change(() -> project.rename(name));
		}
		if (description != null) {
			change(() -> project.describe(description));
		}
		// 암호문은 비교할 수 없어 지정·삭제한 요청을 변경으로 기록 (시크릿과 같음). 값은 남기지 않음
		boolean webhookChanged = webhookUrl != null && (!webhookUrl.isBlank() || project.hasWebhook());
		if (webhookUrl != null) {
			applyWebhook(project, webhookUrl);
		}
		ProjectItem after = ProjectItem.of(project);
		Changes changes = new Changes().compare("name", before.name(), after.name())
			.compare("description", before.description(), after.description());
		if (webhookChanged) {
			changes.add("webhook");
		}
		changeLog.record(actorId, target(project), Action.UPDATED, changes.fields());
		return after;
	}

	/**
	 * @param variables null이면 변수 없음
	 * @param secrets 시크릿 평문. 저장 시 암호화
	 */
	public EnvironmentItem createEnvironment(long projectId, String name, String baseUrl,
			Map<String, String> variables, Map<String, String> secrets, long actorId) {
		Project project = findProject(projectId);
		requireUnusedName("environment", name, null,
				unique -> environments.findByProjectIdAndNameAndArchivedAtIsNull(projectId, unique));
		Environment environment = valid(() -> {
			Environment created = new Environment(project, name, baseUrl);
			created.replaceVariables(variables);
			return created;
		});
		applySecrets(environment, secrets);
		environments.save(environment);
		changeLog.record(actorId, target(environment), Action.CREATED);
		return EnvironmentItem.of(environment);
	}

	/**
	 * @param variables 지정 시 전체 교체
	 * @param secrets 이름별 반영. 값이 있으면 등록·교체, null이면 삭제, 목록에 없는 이름은 유지
	 */
	public EnvironmentItem updateEnvironment(long environmentId, String name, String baseUrl,
			Map<String, String> variables, Map<String, String> secrets, long actorId) {
		Environment environment = findEnvironment(environmentId);
		EnvironmentItem before = EnvironmentItem.of(environment);
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
		applySecrets(environment, secrets);
		EnvironmentItem after = EnvironmentItem.of(environment);
		Changes changes = new Changes().compare("name", before.name(), after.name())
			.compare("baseUrl", before.baseUrl(), after.baseUrl())
			.compareEntries("variables", before.variables(), after.variables());
		// 시크릿은 암호화 값이 매번 달라 비교할 수 없으므로 요청에 있는 이름을 모두 변경으로 기록 (값은 남기지 않음)
		if (secrets != null) {
			secrets.keySet().stream().sorted().forEach(secretName -> changes.add("secrets." + secretName));
		}
		changeLog.record(actorId, target(environment), Action.UPDATED, changes.fields());
		return after;
	}

	public SuiteItem createSuite(long projectId, String name, String description, long actorId) {
		Project project = findProject(projectId);
		requireUnusedName("test suite", name, null,
				unique -> suites.findByProjectIdAndNameAndArchivedAtIsNull(projectId, unique));
		TestSuite suite = suites.save(valid(() -> new TestSuite(project, name, description)));
		changeLog.record(actorId, target(suite), Action.CREATED);
		return SuiteItem.of(suite, 0);
	}

	/**
	 * @param caseOrder 지정 시 케이스 실행 순서 전체 교체. 스위트의 사용 중인 케이스 ID를 빠짐없이 한 번씩
	 * @throws ConflictException caseOrder의 케이스가 스위트의 현재 케이스와 다름 (그사이 추가·삭제됨)
	 * @throws InvalidRequestException caseOrder에 null·중복 ID
	 */
	public SuiteItem updateSuite(long suiteId, String name, String description, List<Long> caseOrder,
			long actorId) {
		TestSuite suite = findSuite(suiteId);
		SuiteItem before = SuiteItem.of(suite, 0);
		Changes changes = new Changes();
		if (caseOrder != null) {
			changes.compare("caseOrder", reorderCases(suiteId, caseOrder), caseOrder);
		}
		long projectId = suite.getProject().getId();
		if (name != null) {
			requireUnusedName("test suite", name, suiteId,
					unique -> suites.findByProjectIdAndNameAndArchivedAtIsNull(projectId, unique));
			change(() -> suite.rename(name));
		}
		if (description != null) {
			change(() -> suite.describe(description));
		}
		SuiteItem after = SuiteItem.of(suite, (int) testCases.countBySuiteIdAndArchivedAtIsNull(suiteId));
		changes.compare("name", before.name(), after.name()).compare("description", before.description(),
				after.description());
		changeLog.record(actorId, target(suite), Action.UPDATED, changes.fields());
		return after;
	}

	/**
	 * 목록 순서대로 실행 순서 1, 2, 3 … 지정
	 * @return 바꾸기 전 순서 (케이스 ID)
	 */
	private List<Long> reorderCases(long suiteId, List<Long> caseOrder) {
		if (caseOrder.stream().anyMatch(Objects::isNull) || new HashSet<>(caseOrder).size() != caseOrder.size()) {
			throw new InvalidRequestException("caseOrder must list each case once");
		}
		List<TestCase> cases = testCases.findActiveInRunOrder(suiteId);
		Map<Long, TestCase> byId = cases.stream().collect(Collectors.toMap(TestCase::getId, testCase -> testCase));
		if (!byId.keySet().equals(Set.copyOf(caseOrder))) {
			throw new ConflictException(
					"the cases of test suite " + suiteId + " have changed; reload and order all current cases");
		}
		for (int i = 0; i < caseOrder.size(); i++) {
			byId.get(caseOrder.get(i)).placeAt(i + 1);
		}
		return cases.stream().map(TestCase::getId).toList();
	}

	/**
	 * 새 케이스는 스위트의 마지막 실행 순서. afterCaseId를 지정하면 그 케이스 바로 뒤 (복제본을 원본 옆에 두기 위함)
	 * @param assertions null 또는 빈 목록이면 기본 판정(status in [2xx])
	 * @param extractors 응답 값 추출 규칙. null이면 없음
	 * @param afterCaseId 바로 앞에 둘 케이스. 같은 스위트의 사용 중인 케이스. null이면 마지막
	 * @throws NotFoundException afterCaseId 케이스 미존재·삭제
	 * @throws InvalidRequestException afterCaseId가 다른 스위트의 케이스
	 */
	public CaseItem createCase(long suiteId, String name, Request request, List<Assertion> assertions,
			List<Extractor> extractors, Long afterCaseId, long actorId) {
		TestSuite suite = findSuite(suiteId);
		TestCase after = afterCaseId == null ? null : findCaseIn(suiteId, afterCaseId);
		requireUnusedName("test case", name, null,
				unique -> testCases.findBySuiteIdAndNameAndArchivedAtIsNull(suiteId, unique));
		TestCase testCase = valid(() -> new TestCase(suite, name, specOf(request), assertions, extractors));
		testCase.placeAt(testCases.findMaxRunOrder(suiteId) + 1);
		testCases.save(testCase);
		if (after != null) {
			placeAfter(suiteId, testCase, after);
		}
		// 뒤 케이스들의 순서가 밀리는 것은 등록에 따른 결과라 스위트 실행 순서 변경으로 따로 기록하지 않음
		changeLog.record(actorId, target(testCase), Action.CREATED);
		return CaseItem.of(testCase);
	}

	private TestCase findCaseIn(long suiteId, long caseId) {
		TestCase found = testCases.findByIdAndArchivedAtIsNull(caseId)
			.orElseThrow(() -> new NotFoundException("test case", caseId));
		if (found.getSuite().getId() != suiteId) {
			throw new InvalidRequestException("afterCaseId " + caseId + " is not a case of test suite " + suiteId);
		}
		return found;
	}

	/** 새 케이스를 after 바로 뒤로 옮기고 스위트 실행 순서를 1부터 다시 매김 */
	private void placeAfter(long suiteId, TestCase created, TestCase after) {
		List<TestCase> ordered = new ArrayList<>(testCases.findActiveInRunOrder(suiteId));
		ordered.remove(created);
		ordered.add(ordered.indexOf(after) + 1, created);
		for (int i = 0; i < ordered.size(); i++) {
			ordered.get(i).placeAt(i + 1);
		}
	}

	/**
	 * @param request 지정 시 요청 정의 전체 교체
	 * @param assertions 지정 시 전체 교체. 빈 목록이면 기본 판정으로 복귀
	 * @param extractors 지정 시 전체 교체
	 */
	public CaseItem updateCase(long caseId, String name, Request request, List<Assertion> assertions,
			List<Extractor> extractors, long actorId) {
		TestCase testCase = testCases.findByIdAndArchivedAtIsNull(caseId)
			.orElseThrow(() -> new NotFoundException("test case", caseId));
		CaseItem before = CaseItem.of(testCase);
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
		if (extractors != null) {
			change(() -> testCase.changeExtractors(extractors));
		}
		CaseItem after = CaseItem.of(testCase);
		changeLog.record(actorId, target(testCase), Action.UPDATED,
				new Changes().compare("name", before.name(), after.name())
					.compare("request", before.request(), after.request())
					.compare("assertions", before.assertions(), after.assertions())
					.compare("extractors", before.extractors(), after.extractors())
					.fields());
		return after;
	}

	public void archiveProject(long projectId, long actorId) {
		Project project = findProject(projectId);
		Instant now = clock.instant();
		environments.findByProjectIdAndArchivedAtIsNullOrderByName(projectId).forEach(environment -> archive(environment, now));
		suites.findByProjectIdAndArchivedAtIsNullOrderByName(projectId).forEach(suite -> archive(suite, now));
		project.archive(now);
		changeLog.record(actorId, target(project), Action.DELETED);
	}

	public void archiveEnvironment(long environmentId, long actorId) {
		Environment environment = findEnvironment(environmentId);
		archive(environment, clock.instant());
		changeLog.record(actorId, target(environment), Action.DELETED);
	}

	public void archiveSuite(long suiteId, long actorId) {
		TestSuite suite = findSuite(suiteId);
		archive(suite, clock.instant());
		changeLog.record(actorId, target(suite), Action.DELETED);
	}

	public void archiveCase(long caseId, long actorId) {
		TestCase testCase = testCases.findByIdAndArchivedAtIsNull(caseId)
			.orElseThrow(() -> new NotFoundException("test case", caseId));
		testCase.archive(clock.instant());
		changeLog.record(actorId, target(testCase), Action.DELETED);
	}

	private static Target target(Project project) {
		return new Target(TargetType.PROJECT, project.getId(), project.getName(), project.getId());
	}

	private static Target target(Environment environment) {
		return new Target(TargetType.ENVIRONMENT, environment.getId(), environment.getName(),
				environment.getProject().getId());
	}

	private static Target target(TestSuite suite) {
		return new Target(TargetType.SUITE, suite.getId(), suite.getName(), suite.getProject().getId(), suite.getId());
	}

	private static Target target(TestCase testCase) {
		return new Target(TargetType.CASE, testCase.getId(), testCase.getName(),
				testCase.getSuite().getProject().getId(), testCase.getSuite().getId());
	}

	/** Webhook 지정·삭제. 주소는 암호화하여 저장하고 평문은 보관하지 않음 */
	private void applyWebhook(Project project, String webhookUrl) {
		if (webhookUrl.isBlank()) {
			project.removeWebhook();
			return;
		}
		String url = valid(() -> WebhookUrls.validate(webhookUrl));
		if (!secretCipher.isEnabled()) {
			throw new InvalidRequestException(
					"secrets are disabled: set sentinel.secret.key (SENTINEL_SECRET_KEY) to use them");
		}
		project.changeWebhook(secretCipher.encrypt(url));
	}

	/**
	 * 시크릿 반영. 값은 암호화하여 저장하고 평문은 보관하지 않음
	 * @throws InvalidRequestException 암호화 키 미설정, 변수와 이름 중복
	 */
	private void applySecrets(Environment environment, Map<String, String> secrets) {
		if (secrets == null || secrets.isEmpty()) {
			return;
		}
		if (!secretCipher.isEnabled()) {
			throw new InvalidRequestException(
					"secrets are disabled: set sentinel.secret.key (SENTINEL_SECRET_KEY) to use them");
		}
		change(() -> secrets.forEach((secretName, value) -> {
			if (value == null) {
				environment.removeSecret(secretName);
			}
			else {
				environment.putSecret(secretName, secretCipher.encrypt(value));
			}
		}));
	}

	private void archive(Environment environment, Instant now) {
		schedules.findByEnvironmentIdAndArchivedAtIsNullOrderById(environment.getId())
			.forEach(schedule -> schedule.archive(now));
		environment.archive(now);
	}

	private void archive(TestSuite suite, Instant now) {
		testCases.findActiveInRunOrder(suite.getId()).forEach(testCase -> testCase.archive(now));
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
