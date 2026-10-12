package com.sentinel.application;

import java.time.Clock;
import java.util.List;

import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestRunRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 기동 시 {@code RUNNING}으로 남은 실행을 {@code ABORTED} 처리. 단일 인스턴스 전제이므로 기동 시점의 RUNNING은 모두 이전 프로세스의 잔존분.
 * <p>
 * 모든 빈 생성 직후(스케줄 폴링·웹 요청 시작 전)에 실행. 기동 완료 이벤트에서 하면 먼저 시작한 첫 폴링이 만든 따라잡기 실행까지
 * 중단 처리됨 (스케줄 작업은 컨텍스트 갱신 이벤트에 시작되어 기동 완료 이벤트보다 앞섬, 2026-10-12 독립 리뷰)
 */
@Component
@RequiredArgsConstructor
class StaleRunRecovery implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);

	private final TestRunRepository runs;

	private final Clock clock;

	private final Transactions transactions;

	@Override
	public void afterSingletonsInstantiated() {
		abortStaleRuns();
	}

	public int abortStaleRuns() {
		int aborted = transactions.write(status -> {
			List<TestRun> stale = runs.findByStatus(TestRun.Status.RUNNING);
			stale.forEach(run -> run.abort(clock.instant()));
			return stale.size();
		});
		if (aborted > 0) {
			log.warn("Aborted {} test run(s) left RUNNING by a previous process", aborted);
		}
		return aborted;
	}

}
