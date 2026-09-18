package com.sentinel;

import org.springframework.boot.SpringApplication;

/**
 * RDS 없이 PostgreSQL 컨테이너로 로컬 실행. {@code ./gradlew bootTestRun}
 */
public class TestSentinelApplication {

	static void main(String[] args) {
		SpringApplication.from(SentinelApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
