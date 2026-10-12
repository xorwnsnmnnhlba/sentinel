package com.sentinel.application;

import java.time.Instant;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestRun;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestResultRepository;
import com.sentinel.infra.persistence.TestRunRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 실행 도중 DB 오류(결과 저장 실패)가 나면 실행을 RUNNING으로 남기지 않고 중단 처리 (2026-10-12 독립 리뷰).
 * 결과 저장소를 감싸 실패를 흉내 내므로 다른 테스트와 컨텍스트를 나눔
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class RunAbortOnErrorTest {

	@MockitoSpyBean
	private TestResultRepository results;

	@Autowired
	private TestRunService testRunService;

	@Autowired
	private TestRunRepository runs;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void abortsARunWhoseResultCannotBeSavedInsteadOfLeavingItRunning() {
		Project project = fixtures.project();
		Environment environment = fixtures.environment(project, Map.of());
		TestSuite suite = fixtures.suite(project);
		fixtures.testCase(suite, HttpMethod.GET, "/health", null, null);
		doThrow(new DataAccessResourceFailureException("connection lost")).when(results).save(any());

		assertThatThrownBy(() -> testRunService.runSuite(suite.getId(), environment.getId(), TestRun.Trigger.SCHEDULED,
				null)).isInstanceOf(DataAccessResourceFailureException.class);

		// RUNNING으로 남으면 재기동 전까지 다시 실행·원문 정리·집계가 막힘
		assertThat(runs.findAll()).singleElement().satisfies(run -> {
			assertThat(run.getStatus()).isEqualTo(TestRun.Status.ABORTED);
			assertThat(run.getFinishedAt()).isBeforeOrEqualTo(Instant.now());
		});
	}

}
