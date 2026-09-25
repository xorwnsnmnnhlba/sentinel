package com.sentinel.infra.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationGuardTest {

	private final DestinationGuard literals = DestinationGuard.system();

	@ParameterizedTest
	@ValueSource(strings = { "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
			"http://169.254.170.2/v2/credentials", "http://[fe80::1]/", "http://[fd00:ec2::254]/latest/meta-data/",
			"http://100.100.100.200/latest/meta-data/", "http://[::ffff:169.254.169.254]/" })
	void blocksLinkLocalAndMetadataAddresses(String url) {
		assertThat(literals.blockedReason(URI.create(url))).hasValueSatisfying(
				reason -> assertThat(reason).contains("link-local or cloud metadata address"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://127.0.0.1:8080/api", "http://10.0.0.5/", "http://192.168.1.10/", "http://[::1]/",
			"http://172.16.0.1/" })
	void allowsLoopbackAndPrivateNetworks(String url) {
		// 내부 API 점검이 목적이므로 사설망은 허용
		assertThat(literals.blockedReason(URI.create(url))).isEmpty();
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

		assertThat(guard.blockedReason(URI.create("http://metadata.google.internal/computeMetadata/v1/")))
			.hasValue("metadata.google.internal resolves to 169.254.169.254, a link-local or cloud metadata address");
		assertThat(guard.blockedReason(URI.create("http://api.internal/health"))).isEmpty();
		// 여러 주소 중 하나라도 막힌 주소면 거부
		assertThat(guard.blockedReason(URI.create("http://mixed.example/"))).isPresent();
		// 해석되지 않는 이름은 전송 단계의 연결 실패로 처리
		assertThat(guard.blockedReason(URI.create("http://unknown.invalid/"))).isEmpty();
	}

}
