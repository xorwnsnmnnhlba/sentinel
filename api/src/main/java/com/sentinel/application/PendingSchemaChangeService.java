package com.sentinel.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.sentinel.application.drift.ResultDrift;
import com.sentinel.application.drift.SchemaDriftService;
import com.sentinel.domain.TestResult;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.runner.schema.SchemaDrift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 대기 구조 변화. 케이스·환경별 마지막 구조 지문이 승인 대기인 것 (docs/design.md "스키마 드리프트 감지" 3단계 3차).
 * <p>
 * 판정이 통과면 실패 알림이 없어 결과 화면을 열기 전에는 구조 변화를 알 수 없으므로 대시보드에 모아 보임.
 * 마지막 지문만 보므로 뒤에 받아들인 결과가 있는 지난 대기(일시적인 오류 응답 등)는 제외. 승인 대기 표시는 결과 화면과 같이 지금 기준선으로
 * 다시 계산 (앞선 결과를 승인해 같은 변화가 반영되었으면 제외). 삭제된 케이스·환경 제외
 */
@Service
@RequiredArgsConstructor
public class PendingSchemaChangeService {

	private final TestResultRepository results;

	private final SchemaDriftService schemaDrift;

	/** @return 가장 심한 등급 순, 같으면 최근 실행 순 */
	@Transactional(readOnly = true)
	public List<PendingSchemaChange> list() {
		List<Long> ids = results.findLatestPendingSchemaResultIds();
		if (ids.isEmpty()) {
			return List.of();
		}
		return results.findAllById(ids)
			.stream()
			.filter(result -> !result.getTestCase().isArchived() && !result.getRun().getEnvironment().isArchived())
			.map(result -> Map.entry(result, schemaDrift.driftOf(result.getRun(), List.of(result), userId -> null)
				.get(result.getId())))
			.filter(entry -> entry.getValue().pending())
			.map(entry -> PendingSchemaChange.of(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparing((PendingSchemaChange change) -> change.changes().getFirst().level())
				.thenComparing(change -> change.run().startedAt(), Comparator.reverseOrder())
				.thenComparing(PendingSchemaChange::id, Comparator.reverseOrder()))
			.toList();
	}

	/**
	 * @param id 승인 대기 결과 (승인 {@code PATCH /api/runs/{runId}/results/{id}})
	 * @param run 결과의 실행
	 * @param changes 지금 기준선으로 비교한 변화. 등급(심한 순) → 경로 순
	 */
	public record PendingSchemaChange(long id, Run run, IdName testCase, IdName suite, IdName project,
			IdName environment, List<SchemaDrift.Change> changes) {

		public PendingSchemaChange {
			changes = List.copyOf(changes);
		}

		static PendingSchemaChange of(TestResult result, ResultDrift drift) {
			return new PendingSchemaChange(result.getId(),
					new Run(result.getRun().getId(), result.getRun().getStartedAt()), IdName.of(result.getTestCase()),
					IdName.of(result.getTestCase().getSuite()), IdName.of(result.getTestCase().getSuite().getProject()),
					IdName.of(result.getRun().getEnvironment()), drift.changes());
		}

	}

	public record Run(long id, Instant startedAt) {
	}

}
