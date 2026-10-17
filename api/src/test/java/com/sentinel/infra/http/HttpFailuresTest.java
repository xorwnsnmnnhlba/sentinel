package com.sentinel.infra.http;

import java.io.IOException;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/** Webhook·생존 신호(JDK HTTP 클라이언트)의 실패 사유. 실행기와 같은 이름 */
class HttpFailuresTest {

	@Test
	void namesCertificateAndTlsFailuresSeparately() {
		ResourceAccessException untrusted = new ResourceAccessException("I/O error on POST request for \"https://hooks.example\"",
				new IOException(new SSLHandshakeException("PKIX path building failed")));
		ResourceAccessException wrongName = new ResourceAccessException("I/O error",
				new SSLPeerUnverifiedException("Certificate for <hooks.example> doesn't match"));

		assertThat(HttpFailures.reason(untrusted)).isEqualTo("TLS_ERROR");
		assertThat(HttpFailures.reason(wrongName)).isEqualTo("TLS_ERROR");
		// 로그에는 주소 없이 종류만
		assertThat(HttpFailures.describe(untrusted)).isEqualTo("TLS error");
		assertThat(HttpFailures.reason(new ResourceAccessException("I/O error", new IOException("reset"))))
			.isEqualTo("CONNECTION_FAILED");
	}

}
