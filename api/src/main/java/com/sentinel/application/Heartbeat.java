package com.sentinel.application;

/**
 * 외부 생존 신호. 스케줄 폴링이 DB까지 정상일 때마다 외부 감시 서비스(healthchecks.io, Uptime Kuma 푸시 등)에 알림.
 * 신호가 끊기면 외부 서비스가 알리므로, Sentinel이 멈춘 동안에도(모니터링 공백 알림은 재개 후에야 발송) 바로 알 수 있음
 */
public interface Heartbeat {

	/** 신호 주소 미지정 시 */
	Heartbeat NONE = () -> {
	};

	/** 신호 발송. 실패해도 예외를 던지지 않음 (폴링을 멈추지 않도록) */
	void beat();

}
