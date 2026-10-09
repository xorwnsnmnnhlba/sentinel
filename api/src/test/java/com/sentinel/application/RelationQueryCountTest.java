package com.sentinel.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestSuite;
import com.sentinel.runner.HttpMethod;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목록 응답의 관계 이름({id, name})을 읽느라 항목 수만큼 조회하지 않는지. 가리키는 리소스가 1개일 때와 3개일 때 조회 수가 같아야 함
 */
// 쿼리 수를 세므로 같은 컨텍스트에서 쿼리를 보내는 스케줄 폴링·주간 요약 확인을 끔 (통계는 SessionFactory 전체 기준)
@SpringBootTest(properties = { "sentinel.schedule.enabled=false",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class RelationQueryCountTest {

	private static final long ACTOR = 1L;

	@Autowired
	private CatalogQueryService catalog;

	@Autowired
	private ScheduleService schedules;

	@Autowired
	private MaintenanceWindowService maintenanceWindows;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void listsNameTheirRelationsWithoutAQueryPerItem() {
		addProject();
		long environments = queries(() -> catalog.listEnvironments(null));
		long suites = queries(() -> catalog.listSuites(null));
		long cases = queries(() -> catalog.listCases(null));
		long scheduleList = queries(() -> schedules.list(null));
		long windows = queries(() -> maintenanceWindows.listOpen(null));

		addProject();
		addProject();

		assertThat(queries(() -> catalog.listEnvironments(null))).isEqualTo(environments);
		assertThat(queries(() -> catalog.listSuites(null))).isEqualTo(suites);
		assertThat(queries(() -> catalog.listCases(null))).isEqualTo(cases);
		assertThat(queries(() -> schedules.list(null))).isEqualTo(scheduleList);
		assertThat(queries(() -> maintenanceWindows.listOpen(null))).isEqualTo(windows);
		// 실제로 이름이 채워짐
		assertThat(schedules.list(null)).hasSize(3).allSatisfy(schedule -> {
			assertThat(schedule.project().name()).startsWith("project-");
			assertThat(schedule.suite().name()).startsWith("suite-");
			assertThat(schedule.environment().name()).isEqualTo("dev");
		});
	}

	/** 프로젝트 하나에 환경·스위트·케이스·스케줄·진행 중인 점검 시간 1개씩 */
	private void addProject() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/health", null, null);
		schedules.create(suite.getId(), environment.getId(), "10 9 * * *", ACTOR);
		Instant now = Instant.now();
		maintenanceWindows.create(environment.getId(), now.minusSeconds(60), now.plus(Duration.ofHours(1)), null, ACTOR);
	}

	private long queries(Supplier<?> list) {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		list.get();
		return statistics.getPrepareStatementCount();
	}

}
