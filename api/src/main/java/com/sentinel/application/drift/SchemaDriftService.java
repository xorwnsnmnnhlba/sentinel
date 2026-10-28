package com.sentinel.application.drift;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.sentinel.application.IdName;
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
 * 승인 대기는 사용자가 승인하면 받아들인 경우와 같이 기준선에 넣음 (2차).
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
	 * @param userName 승인한 사용자 이름 조회. 찾을 수 없으면 null
	 * @return 결과 ID별 구조 변화. 지문이 없는 결과(JSON이 아닌 응답, 응답 없음)는 제외
	 */
	public Map<Long, ResultDrift> driftOf(TestRun run, List<TestResult> runResults,
			Function<Long, IdName> userName) {
		List<TestResult> fingerprinted = runResults.stream().filter(result -> result.getResponseSchema() != null).toList();
		if (fingerprinted.isEmpty()) {
			return Map.of();
		}
		List<Long> caseIds = fingerprinted.stream().map(result -> result.getTestCase().getId()).distinct().toList();
		long environmentId = run.getEnvironment().getId();
		long firstResultId = runResults.stream().mapToLong(TestResult::getId).min().orElseThrow();
		Map<Long, List<ResponseSchema>> previous = previousSchemas(environmentId, caseIds, run.getId(), firstResultId);
		Map<Long, Set<SchemaTolerance>> tolerances = tolerances(environmentId, caseIds, firstResultId);

		Map<Long, IdName> approvers = new HashMap<>();
		Map<Long, ResultDrift> drifts = new HashMap<>();
		for (TestResult result : fingerprinted) {
			long caseId = result.getTestCase().getId();
			ResultDrift judged = judge(previous.getOrDefault(caseId, List.of()),
					tolerances.getOrDefault(caseId, Set.of()), result.getResponseSchema());
			ResultDrift.Approval approval = result.getSchemaApprovedAt() == null ? null
					: new ResultDrift.Approval(
							approvers.computeIfAbsent(result.getSchemaApprovedBy(), userName),
							result.getSchemaApprovedAt());
			drifts.put(result.getId(), new ResultDrift(judged.status(), judged.observations(), judged.changes(),
					result.isSchemaPending() && holds(judged), approval));
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
		Basis basis = basis(environmentId, saved);
		if (holds(judge(basis.history(), basis.known(), schema))) {
			saved.holdSchema();
			return;
		}
		accept(environmentId, saved, basis);
	}

	/**
	 * 승인 대기 결과의 지문을 기준선에 반영 (기록할 때 받아들인 경우와 같이 그 지문까지의 변동을 영구 허용으로 남김).
	 * 그 뒤 실행의 판정도 반영된 기준선으로 다시 계산하므로 같은 변화가 이어진 결과는 더 이상 승인 대기로 표시하지 않음.
	 * 트랜잭션 안에서 호출
	 * @param approvedBy 승인한 사용자
	 * @return 반영 여부. 승인 대기가 아니면(이미 반영했거나 기록할 때 받아들인 결과) false
	 */
	public boolean approve(TestResult result, long approvedBy, Instant approvedAt) {
		if (results.approveSchemaIfPending(result.getId(), approvedBy, approvedAt) == 0) {
			return false;
		}
		long environmentId = result.getRun().getEnvironment().getId();
		accept(environmentId, result, basis(environmentId, result));
		return true;
	}

	/** 기준선(최근 순)에 이 지문을 더해 오래된 순으로 변동을 찾고, 새로 본 것만 기록 */
	private void accept(long environmentId, TestResult result, Basis basis) {
		long caseId = result.getTestCase().getId();
		List<ResponseSchema> accepted = new ArrayList<>(basis.history().reversed());
		accepted.add(result.getResponseSchema());
		SchemaTolerance.observedIn(accepted)
			.stream()
			.filter(tolerance -> !basis.known().contains(tolerance)
					&& tolerance.path().length() <= MAX_TOLERANCE_PATH)
			.forEach(tolerance -> results.addSchemaTolerance(caseId, environmentId, tolerance.path(),
					tolerance.kind().name(), result.getId()));
	}

	/** 결과 1건의 기준선: 앞선 실행의 받아들인 지문(최근 순)과 그보다 앞서 남긴 영구 허용 */
	private Basis basis(long environmentId, TestResult result) {
		long caseId = result.getTestCase().getId();
		return new Basis(
				previousSchemas(environmentId, List.of(caseId), result.getRun().getId(), result.getId())
					.getOrDefault(caseId, List.of()),
				tolerances(environmentId, List.of(caseId), result.getId()).getOrDefault(caseId, Set.of()));
	}

	private record Basis(List<ResponseSchema> history, Set<SchemaTolerance> known) {
	}

	/** 승인 없이 기준선에 넣지 않는 변화(호환이 깨지는 변화·null 전환)가 있음 */
	private static boolean holds(ResultDrift drift) {
		return drift.changes().stream().anyMatch(change -> change.level() != SchemaDrift.Level.INFO);
	}

	private ResultDrift judge(List<ResponseSchema> history, Set<SchemaTolerance> tolerances, ResponseSchema schema) {
		return history.size() < properties.minObservations()
				? new ResultDrift(ResultDrift.Status.COLLECTING, history.size(), List.of(), false, null)
				: new ResultDrift(ResultDrift.Status.COMPARED, history.size(),
						SchemaDrift.compare(SchemaBaseline.of(history).tolerating(tolerances), schema), false, null);
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
