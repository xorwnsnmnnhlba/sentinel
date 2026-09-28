package com.sentinel.application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 수동 실행마다 가상 스레드 하나. 대부분 GW 응답 대기이므로 스레드를 점유하지 않는 가상 스레드 사용.
 * 동시 실행 수 제한은 {@link TestRunService}가 담당. 앱 종료 시 진행 중인 실행은 재기동 때 중단(ABORTED) 처리
 */
@Component
class VirtualThreadBackgroundRuns implements BackgroundRuns {

	private static final Logger log = LoggerFactory.getLogger(VirtualThreadBackgroundRuns.class);

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@Override
	public void submit(Runnable run) {
		executor.execute(() -> {
			try {
				run.run();
			}
			catch (RuntimeException ex) {
				// 케이스별 실패는 결과로 기록하므로 여기 오는 예외는 DB 장애 등. 실행은 RUNNING으로 남고 재기동 시 ABORTED
				log.error("Manual run failed unexpectedly", ex);
			}
		});
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
