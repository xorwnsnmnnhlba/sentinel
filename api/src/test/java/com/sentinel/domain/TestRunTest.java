package com.sentinel.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRunTest {

	private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

	private final Project project = new Project("billing", null);

	private final TestRun run = TestRun.start(new TestSuite(project, "charge", null),
			new Environment(project, "dev", "https://gw"), TestRun.Trigger.SCHEDULED, NOW);

	@Test
	void treatsARunOfEveryCaseAsAWholeSuiteRun() {
		assertThat(run.isWholeSuite()).isTrue();
		assertThat(run.isRerun()).isFalse();
	}

	@Test
	void refusesToRerunARunStillInProgress() {
		assertThatThrownBy(() -> TestRun.startRerun(run, NOW, 1L)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("is still running");
	}

}
