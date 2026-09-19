package com.sentinel.infra.persistence;

import java.net.SocketTimeoutException;
import java.time.Duration;

import javax.sql.DataSource;

import com.sentinel.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 응답 없는 서버를 {@code pg_sleep}으로 모사하여 socketTimeout이 드라이버에 적용되는지 확인. 테스트 시간 단축을 위해 2초로 재정의.
 * <p>
 * 타임아웃 후 커넥션이 끊기므로 테스트 트랜잭션을 사용하지 않음.
 */
@DataJpaTest(properties = "spring.datasource.hikari.data-source-properties.socketTimeout=2")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatabaseTimeoutTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void passesDriverTimeoutPropertiesFromConfiguration() {
		assertThat(((HikariDataSource) dataSource).getDataSourceProperties())
			.containsEntry("socketTimeout", "2")
			.containsEntry("connectTimeout", "10")
			.containsEntry("tcpKeepAlive", "true");
	}

	@Test
	void failsInsteadOfWaitingForeverWhenServerStopsResponding() {
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> jdbcTemplate.execute("SELECT pg_sleep(5)"))
			.isInstanceOf(DataAccessException.class)
			.satisfies(ex -> assertThat(NestedExceptionUtils.getRootCause(ex)).isInstanceOf(SocketTimeoutException.class));

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
		assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
	}

}
