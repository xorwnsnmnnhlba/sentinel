package com.sentinel.application.drift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.runner.schema.ResponseSchema;
import com.sentinel.runner.schema.SchemaBaseline;
import com.sentinel.runner.schema.SchemaDrift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 결과의 구조 변화 판정 (docs/design.md "스키마 드리프트 감지" 2단계)
 * <p>
 * 기준선은 저장하지 않고 조회 시점에 계산: 같은 케이스·환경에서 이 실행보다 앞선 실행의 지문 최근 {@code window}건.
 * 실행 1건의 모든 케이스를 쿼리 1회로 조회. 기준선 저장·승인·알림은 3단계
 */
@Service
@RequiredArgsConstructor
public class SchemaDriftService {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

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
		Map<Long, List<ResponseSchema>> previous = new HashMap<>();
		long firstResultId = runResults.stream().mapToLong(TestResult::getId).min().orElseThrow();
		for (Object[] row : results.findRecentSchemas(run.getEnvironment().getId(), caseIds, run.getId(),
				firstResultId, properties.window())) {
			long caseId = ((Number) row[0]).longValue();
			previous.computeIfAbsent(caseId, id -> new ArrayList<>())
				.add(JSON.readValue(Objects.toString(row[1]), ResponseSchema.class));
		}

		Map<Long, ResultDrift> drifts = new HashMap<>();
		for (TestResult result : fingerprinted) {
			List<ResponseSchema> history = previous.getOrDefault(result.getTestCase().getId(), List.of());
			drifts.put(result.getId(), history.size() < properties.minObservations()
					? new ResultDrift(ResultDrift.Status.COLLECTING, history.size(), List.of())
					: new ResultDrift(ResultDrift.Status.COMPARED, history.size(),
							SchemaDrift.compare(SchemaBaseline.of(history), result.getResponseSchema())));
		}
		return drifts;
	}

}
