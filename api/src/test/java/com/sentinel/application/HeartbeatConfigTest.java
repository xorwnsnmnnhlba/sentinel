package com.sentinel.application;

import java.time.Duration;
import java.util.List;

import com.sentinel.infra.http.HttpHeartbeat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HeartbeatConfigTest {

	@Test
	void sendsNothingWithoutAnAddress() {
		assertThat(SchedulingConfig.heartbeat(new HeartbeatProperties(null, Duration.ofSeconds(5))))
			.isSameAs(Heartbeat.NONE);
		assertThat(SchedulingConfig.heartbeat(new HeartbeatProperties("https://hc-ping.com/abc", Duration.ofSeconds(5))))
			.isInstanceOf(HttpHeartbeat.class);
	}

	@Test
	void validatesTheAddressWithoutEchoingIt() {
		assertThat(new HeartbeatProperties(null, Duration.ofSeconds(5)).enabled()).isFalse();
		assertThat(new HeartbeatProperties("  ", Duration.ofSeconds(5)).enabled()).isFalse();
		HeartbeatProperties configured = new HeartbeatProperties(" https://hc-ping.com/abc ", Duration.ofSeconds(5));
		assertThat(configured.enabled()).isTrue();
		assertThat(configured.url()).isEqualTo("https://hc-ping.com/abc");

		for (String invalid : List.of("hc-ping.com/secret-token", "ftp://host/secret-token", "https:///secret-token",
				"http://bad host/secret-token")) {
			assertThatIllegalArgumentException().isThrownBy(() -> new HeartbeatProperties(invalid, Duration.ofSeconds(5)))
				.withMessage("sentinel.heartbeat.url must be an absolute http(s) URL");
		}
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new HeartbeatProperties("https://hc-ping.com/abc", Duration.ZERO));
	}

}
