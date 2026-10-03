package com.sentinel.application;

import java.time.Duration;

import com.sentinel.infra.http.HttpHeartbeat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatConfigTest {

	@Test
	void sendsNothingWithoutAnAddress() {
		assertThat(SchedulingConfig.heartbeat(new HeartbeatProperties(null, Duration.ofSeconds(5))))
			.isSameAs(Heartbeat.NONE);
		assertThat(SchedulingConfig.heartbeat(new HeartbeatProperties("https://hc-ping.com/abc", Duration.ofSeconds(5))))
			.isInstanceOf(HttpHeartbeat.class);
	}

}
