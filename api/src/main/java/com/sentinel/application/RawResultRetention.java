package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.sentinel.domain.BaseEntity;
import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 보관기간이 지난 실행 결과의 요청·응답 원문(헤더·본문) 정리.
 * <p>
 * 실행 이력은 핵심 자산이므로 행은 지우지 않고 용량이 큰 원문만 비움. 판정·규칙별 결과·상태 코드·소요 시간은 유지.
 * 배치 단위로 트랜잭션을 나누어 도중 실패해도 앞서 정리한 분은 유지되고, 다음 실행에서 나머지를 이어서 정리.
 */
@Component
@RequiredArgsConstructor
public class RawResultRetention {

	private static final Logger log = LoggerFactory.getLogger(RawResultRetention.class);

	private final TestRunRepository runs;

	private final TestResultRepository results;

	private final RetentionProperties properties;

	private final Clock clock;

	private final Transactions transactions;

	public Purged purgeExpired() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(properties.rawRetention());
		Purged total = Purged.NONE;
		Purged batch;
		do {
			batch = transactions.write(status -> purgeBatch(cutoff, now));
			total = total.plus(batch);
		}
		while (batch.runs() == properties.batchSize());
		if (total.runs() > 0) {
			log.info("Purged raw request/response of {} result(s) in {} run(s) started before {}", total.results(),
					total.runs(), cutoff);
		}
		return total;
	}

	private Purged purgeBatch(Instant cutoff, Instant now) {
		List<TestRun> targets = runs.findRawPurgeTargets(cutoff, TestRun.Status.RUNNING,
				properties.batchSize());
		if (targets.isEmpty()) {
			return Purged.NONE;
		}
		long purgedResults = results.purgeRawByRunIds(targets.stream().map(BaseEntity::getId).toList());
		targets.forEach(run -> run.markRawPurged(now));
		return new Purged(targets.size(), purgedResults);
	}

	/**
	 * @param runs 원문을 정리한 실행 수
	 * @param results 원문을 비운 결과 건수
	 */
	public record Purged(int runs, long results) {

		static final Purged NONE = new Purged(0, 0);

		Purged plus(Purged other) {
			return new Purged(runs + other.runs, results + other.results);
		}

	}

}
