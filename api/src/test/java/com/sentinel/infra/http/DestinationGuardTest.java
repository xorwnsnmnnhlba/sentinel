package com.sentinel.infra.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationGuardTest {

	private final DestinationGuard literals = DestinationGuard.system();

	@ParameterizedTest
	@ValueSource(strings = { "169.254.169.254", "169.254.170.2", "[fe80::1]", "fe80::1", "[fd00:ec2::254]",
			"100.100.100.200", "[::ffff:169.254.169.254]" })
	void blocksLinkLocalAndMetadataAddresses(String host) {
		assertThatThrownBy(() -> literals.resolve(host)).isInstanceOf(BlockedDestinationException.class)
			.hasMessageContaining("link-local or cloud metadata address");
	}

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "10.0.0.5", "192.168.1.10", "[::1]", "172.16.0.1" })
	void allowsLoopbackAndPrivateNetworks(String host) throws UnknownHostException {
		// 내부 API 점검이 목적이므로 사설망은 허용
		assertThat(literals.resolve(host)).hasSize(1);
	}

	@Test
	void judgesHostNamesByTheAddressTheyResolveTo() throws UnknownHostException {
		Map<String, InetAddress[]> dns = Map.of("metadata.google.internal",
				new InetAddress[] { InetAddress.getByName("169.254.169.254") }, "api.internal",
				new InetAddress[] { InetAddress.getByName("10.1.2.3") }, "mixed.example",
				new InetAddress[] { InetAddress.getByName("10.1.2.3"), InetAddress.getByName("169.254.169.254") });
		DestinationGuard guard = new DestinationGuard(host -> {
			InetAddress[] found = dns.get(host);
			if (found == null) {
				throw new UnknownHostException(host);
			}
			return found;
		});

		assertThatThrownBy(() -> guard.resolve("metadata.google.internal"))
			.isInstanceOf(BlockedDestinationException.class)
			.hasMessage("metadata.google.internal resolves to 169.254.169.254, a link-local or cloud metadata address");
		// 확인한 주소를 그대로 연결에 사용
		assertThat(guard.resolve("api.internal")).containsExactly(InetAddress.getByName("10.1.2.3"));
		// 여러 주소 중 하나라도 막힌 주소면 거부
		assertThatThrownBy(() -> guard.resolve("mixed.example")).isInstanceOf(BlockedDestinationException.class);
		// 해석되지 않는 이름은 일반 해석 실패 (실행기에서 연결 실패로 처리)
		assertThatThrownBy(() -> guard.resolve("unknown.invalid")).isExactlyInstanceOf(UnknownHostException.class);
	}

}
