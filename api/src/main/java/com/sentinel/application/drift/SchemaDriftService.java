package com.sentinel.application.drift;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.runner.schema.ResponseSchema;
import com.sentinel.runner.schema.SchemaBaseline;
import com.sentinel.runner.schema.SchemaDrift;
import com.sentinel.runner.schema.SchemaTolerance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 결과의 구조 변화 판정 (docs/design.md "스키마 드리프트 감지" 2·3단계)
 * <p>
 * 기준선: 같은 케이스·환경에서 이 실행보다 앞선 실행 중 받아들인(승인 대기가 아닌) 지문 최근 {@code window}건 + 영구 허용 변동
 * ({@link SchemaTolerance}). 결과를 기록할 때 판정하여 호환이 깨지는 변화(BREAKING)·null 전환(WARNING)이 있으면 승인 대기로 두고
 * 기준선에 넣지 않음. 새 필드(INFO)만 있거나 기준선 수집 중이면 받아들이고 그 지문까지의 변동을 영구 허용으로 남김.
 * 조회는 같은 기준으로 다시 계산 (실행 1건의 모든 케이스를 지문·허용 각 쿼리 1회로)
 */
@Service
@RequiredArgsConstructor
public class SchemaDriftService {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	/** 영구 허용으로 남길 경로 최대 길이 (V31 {@code schema_tolerance.path}). 넘으면 창 안에서만 허용 */
	private static final int MAX_TOLERANCE_PATH = 1000;

	private final TestResultRepository results;

	private final SchemaDriftProperties properties;

	/**
	 * 트랜잭션 안에서 호출
	 * @return 결과 ID별 구조 변화. 지문이 없는 결과(JSON이 아닌 응답, 응답 없음)는 제외
	 */
	public Map<Long, ResultDrift> driftOf(TestRun run, List<TestResult> runResults) {
		List<TestResult> fingerprinted = runResults.stream().filter(result -> result.getResponseSchema() != null).toList();
		if (fingerprinted.isEmpty()) {
			return Map.of();
		}
		List<Long> caseIds = fingerprinted.stream().map(result -> result.getTestCase().getId()).distinct().toList();
		long environmentId = run.getEnvironment().getId();
		long firstResultId = runResults.stream().mapToLong(TestResult::getId).min().orElseThrow();
		Map<Long, List<ResponseSchema>> previous = previousSchemas(environmentId, caseIds, run.getId(), firstResultId);
		Map<Long, Set<SchemaTolerance>> tolerances = tolerances(environmentId, caseIds, firstResultId);

		Map<Long, ResultDrift> drifts = new HashMap<>();
		for (TestResult result : fingerprinted) {
			long caseId = result.getTestCase().getId();
			drifts.put(result.getId(), judge(previous.getOrDefault(caseId, List.of()),
					tolerances.getOrDefault(caseId, Set.of()), result.getResponseSchema()));
		}
		return drifts;
	}

	/**
	 * 저장한 결과의 구조 변화를 판정하여 승인 대기로 두거나 받아들임. 결과를 저장한 트랜잭션 안에서 호출
	 * @param environmentId 실행 환경
	 */
	public void review(long environmentId, TestResult saved) {
		ResponseSchema schema = saved.getResponseSchema();
		if (schema == null) {
			return;
		}
		long caseId = saved.getTestCase().getId();
		List<ResponseSchema> history = previousSchemas(environmentId, List.of(caseId), saved.getRun().getId(),
				saved.getId())
			.getOrDefault(caseId, List.of());
		Set<SchemaTolerance> known = tolerances(environmentId, List.of(caseId), saved.getId())
			.getOrDefault(caseId, Set.of());
		boolean held = judge(history, known, schema).changes()
			.stream()
			.anyMatch(change -> change.level() != SchemaDrift.Level.INFO);
		if (held) {
			saved.holdSchema();
			return;
		}
		// 기준선(최근 순)에 이 지문을 더해 오래된 순으로 변동을 찾고, 새로 본 것만 기록
		List<ResponseSchema> accepted = new ArrayList<>(history.reversed());
		accepted.add(schema);
		SchemaTolerance.observedIn(accepted)
			.stream()
			.filter(tolerance -> !known.contains(tolerance) && tolerance.path().length() <= MAX_TOLERANCE_PATH)
			.forEach(tolerance -> results.addSchemaTolerance(caseId, environmentId, tolerance.path(),
					tolerance.kind().name(), saved.getId()));
	}

	private ResultDrift judge(List<ResponseSchema> history, Set<SchemaTolerance> tolerances, ResponseSchema schema) {
		return history.size() < properties.minObservations()
				? new ResultDrift(ResultDrift.Status.COLLECTING, history.size(), List.of())
				: new ResultDrift(ResultDrift.Status.COMPARED, history.size(),
						SchemaDrift.compare(SchemaBaseline.of(history).tolerating(tolerances), schema));
	}

	/** @return 케이스 ID별 이전 지문 (최근 순) */
	private Map<Long, List<ResponseSchema>> previousSchemas(long environmentId, Collection<Long> caseIds, long runId,
			long beforeResultId) {
		Map<Long, List<ResponseSchema>> previous = new HashMap<>();
		for (Object[] row : results.findRecentSchemas(environmentId, caseIds, runId, beforeResultId,
				properties.window())) {
			previous.computeIfAbsent(((Number) row[0]).longValue(), id -> new ArrayList<>())
				.add(JSON.readValue(Objects.toString(row[1]), ResponseSchema.class));
		}
		return previous;
	}

	private Map<Long, Set<SchemaTolerance>> tolerances(long environmentId, Collection<Long> caseIds,
			long beforeResultId) {
		Map<Long, Set<SchemaTolerance>> tolerances = new HashMap<>();
		for (Object[] row : results.findSchemaTolerances(environmentId, caseIds, beforeResultId)) {
			tolerances.computeIfAbsent(((Number) row[0]).longValue(), id -> new HashSet<>())
				.add(new SchemaTolerance((String) row[1], SchemaTolerance.Kind.valueOf((String) row[2])));
		}
		return tolerances;
	}

}
