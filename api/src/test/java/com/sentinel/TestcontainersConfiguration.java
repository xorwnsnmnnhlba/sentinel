package com.sentinel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 DB. PostgreSQL 컨테이너는 테스트 실행(JVM) 전체에서 하나만 띄우고, 테스트 컨텍스트마다 그 안에 새 데이터베이스를 만들어 연결.
 * <p>
 * 컨테이너를 컨텍스트마다 띄우면 Spring이 캐시해 둔 컨텍스트 수만큼(최대 32) 컨테이너가 동시에 떠(전체 테스트 중 최대 33개) 새 컨테이너의
 * 기동·첫 접속이 느려지고 가끔 접속 시간 초과로 컨텍스트 로드가 실패했음 (2026-10-17). 데이터베이스는 컨텍스트마다 따로라 격리는 그대로.
 * 컨테이너는 Testcontainers(Ryuk)가 JVM 종료 시 정리
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final PostgreSQLContainer POSTGRES = startPostgres();

	private static final AtomicInteger DATABASES = new AtomicInteger();

	private static PostgreSQLContainer startPostgres() {
		// 캐시된 컨텍스트(최대 32)가 각자 연결 풀(최대 5, application.yml)을 열므로 기본 한도 100으로는 부족 ("too many clients")
		PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
			.withCommand("postgres", "-c", "max_connections=300");
		container.start();
		return container;
	}

	/** 이 컨텍스트 전용 데이터베이스 */
	@Bean
	JdbcConnectionDetails jdbcConnectionDetails() {
		String database = "context_" + DATABASES.incrementAndGet();
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + database);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("could not create test database " + database, ex);
		}
		String url = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
				POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), database);
		return new JdbcConnectionDetails() {

			@Override
			public String getUsername() {
				return POSTGRES.getUsername();
			}

			@Override
			public String getPassword() {
				return POSTGRES.getPassword();
			}

			@Override
			public String getJdbcUrl() {
				return url;
			}

		};
	}

}
