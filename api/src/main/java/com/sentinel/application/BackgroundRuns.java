package com.sentinel.application;

/**
 * 수동 실행을 요청 스레드 밖에서 진행. 실행 완료를 기다려야 하는 테스트는 호출한 스레드에서 바로 진행하는 구현으로 교체
 */
public interface BackgroundRuns {

	void submit(Runnable run);

}
