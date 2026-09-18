package com.sentinel.application;

import java.time.Clock;
import java.util.List;

import com.sentinel.domain.TestRun;
import com.sentinel.infra.persistence.TestRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동 시 {@code RUNNING}으로 남은 실행을 {@code ABORTED} 처리. 단일 인스턴스 전제이므로 기동 시점의 RUNNING은 모두 이전 프로세스의 잔존분.
 */
@Component
public class StaleRunRecovery {

	private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);

	private final TestRunRepository runs;

	private final Clock clock;

	private final TransactionTemplate transaction;

	StaleRunRecovery(TestRunRepository runs, Clock clock, PlatformTransactionManager transactionManager) {
		this.runs = runs;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@EventListener(ApplicationReadyEvent.class)
	public int abortStaleRuns() {
		int aborted = transaction.execute(status -> {
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
