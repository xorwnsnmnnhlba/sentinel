package com.sentinel.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 수동 실행 (화면·API에서 시작한 실행). 요청은 실행을 만들고 바로 응답하며 실제 실행은 백그라운드에서 진행
 *
 * @param maxConcurrent 동시에 진행할 수 있는 수동 실행 수. 넘으면 새 실행을 거부 (GW 동시 호출·DB 커넥션 점유 제한)
 */
@ConfigurationProperties("sentinel.runner.manual")
public record ManualRunProperties(@DefaultValue("4") int maxConcurrent) {

	public ManualRunProperties {
		if (maxConcurrent < 1) {
			throw new IllegalArgumentException("sentinel.runner.manual.max-concurrent must be at least 1");
		}
	}

}
