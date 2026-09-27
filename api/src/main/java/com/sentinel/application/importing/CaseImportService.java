package com.sentinel.application.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService.CaseItem;
import com.sentinel.application.CatalogQueryService.Request;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.application.NotFoundException;
import com.sentinel.domain.TestCase;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.runner.assertion.Assertion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 문서에서 케이스 가져오기.
 * <p>
 * 미리보기는 문서를 케이스 초안으로 변환만 하고 저장하지 않음. 가져오기는 문서 형식과 무관하게 초안 목록을 받아 한 트랜잭션으로 등록하며,
 * 한 건이라도 실패(이름 중복, 잘못된 요청 정의)하면 전부 등록하지 않음.
 */
@Service
@RequiredArgsConstructor
public class CaseImportService {

	/** 한 번에 가져올 수 있는 케이스 수 */
	public static final int MAX_CASES = 500;

	private final OpenApiConverter openApiConverter = new OpenApiConverter();

	private final PostmanConverter postmanConverter = new PostmanConverter();

	private final TestSuiteRepository suites;

	private final TestCaseRepository testCases;

	private final CatalogCommandService commands;

	public enum Format {
		/** OpenAPI 3.0·3.1 (JSON·YAML) */
		OPENAPI_3,
		/** Postman Collection v2.0·v2.1 (JSON) */
		POSTMAN
	}

	/**
	 * @throws NotFoundException 스위트 미존재·보관
	 * @throws InvalidRequestException 해석할 수 없거나 지원하지 않는 문서
	 */
	@Transactional(readOnly = true)
	public Preview preview(long suiteId, Format format, String content) {
		suites.findByIdAndArchivedAtIsNull(suiteId).orElseThrow(() -> new NotFoundException("test suite", suiteId));
		ConvertedDocument document = switch (format) {
			case OPENAPI_3 -> openApiConverter.convert(content);
			case POSTMAN -> postmanConverter.convert(content);
		};
		Set<String> existingNames = testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suiteId)
			.stream()
			.map(TestCase::getName)
			.collect(Collectors.toSet());
		List<Candidate> candidates = document.cases()
			.stream()
			.map(draft -> Candidate.of(draft, existingNames.contains(draft.name())))
			.toList();
		return new Preview(document.title(), document.version(), document.servers(), candidates, document.warnings());
	}

	/**
	 * @return 등록한 케이스. 요청 순서 유지
	 * @throws InvalidRequestException 빈 목록, 최대 건수 초과, 잘못된 케이스 (몇 번째 케이스인지 포함)
	 * @throws com.sentinel.application.ConflictException 스위트에 이미 있거나 목록 안에서 겹치는 이름
	 */
	@Transactional
	public List<CaseItem> importCases(long suiteId, List<NewCase> cases) {
		if (cases == null || cases.isEmpty()) {
			throw new InvalidRequestException("cases must not be empty");
		}
		if (cases.size() > MAX_CASES) {
			throw new InvalidRequestException("at most " + MAX_CASES + " cases can be imported at once");
		}
		List<CaseItem> created = new ArrayList<>();
		for (int index = 0; index < cases.size(); index++) {
			NewCase newCase = cases.get(index);
			if (newCase == null) {
				throw new InvalidRequestException("cases[" + index + "] must not be null");
			}
			try {
				created.add(commands.createCase(suiteId, newCase.name(), newCase.request(), newCase.assertions(), null));
			}
			catch (InvalidRequestException ex) {
				throw new InvalidRequestException("cases[" + index + "] '" + newCase.name() + "': " + ex.getMessage());
			}
		}
		return created;
	}

	/**
	 * @param servers 문서의 서버 주소. 환경 기본 URL 설정 참고용
	 * @param warnings 문서 단위 경고
	 */
	public record Preview(String title, String version, List<String> servers, List<Candidate> cases,
			List<String> warnings) {
	}

	/**
	 * @param source 원본 작업. 예: {@code GET /pets/{petId}}
	 * @param conflict 스위트에 같은 이름의 케이스가 있음. 이름을 바꾸거나 제외해야 가져오기 가능
	 */
	public record Candidate(String source, String name, Request request, List<Assertion> assertions,
			List<String> warnings, boolean conflict) {

		static Candidate of(CaseDraft draft, boolean conflict) {
			return new Candidate(draft.source(), draft.name(), draft.request(), draft.assertions(), draft.warnings(),
					conflict);
		}

	}

	/**
	 * @param assertions 생략 또는 빈 목록이면 기본 판정(status in [2xx])
	 */
	public record NewCase(String name, Request request, List<Assertion> assertions) {
	}

}
