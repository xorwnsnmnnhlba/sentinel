package com.sentinel.infra.http;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 주소마다 다시 시도하는 TLS 연결 수립의 실패 기록 (IPv4·IPv6 주소가 모두 있는 이름) */
class TlsFailureRecorderTest {

	/** 미리 정한 순서대로 실패하거나 성공하는 TLS 단계 */
	private final Deque<Object> outcomes = new ArrayDeque<>();

	private final TlsFailureRecorder recorder = new TlsFailureRecorder((socket, target, port, attachment, context) -> {
		Object next = outcomes.removeFirst();
		if (next instanceof IOException failure) {
			throw failure;
		}
		return (SSLSocket) next;
	});

	@Test
	void keepsTheTlsFailureWhileTheNextAddressDoesNotGetThatFar() {
		SSLHandshakeException untrusted = new SSLHandshakeException("PKIX path building failed");
		outcomes.add(untrusted);

		assertThatThrownBy(() -> upgrade()).isSameAs(untrusted);

		// 다음 주소는 연결 거부라 TLS 단계까지 오지 않음 → 기록 유지
		assertThat(recorder.lastFailure()).isSameAs(untrusted);
	}

	@Test
	void forgetsTheFailureWhenTheNextAddressCompletesTls() throws IOException {
		// 다음 주소의 TLS가 성공한 뒤의 실패(예: 응답 시간 초과)를 인증서 오류로 보고하지 않도록
		outcomes.add(new SSLPeerUnverifiedException("Certificate for <api.example> doesn't match"));
		SSLSocket upgraded = mock(SSLSocket.class);
		outcomes.add(upgraded);

		assertThatThrownBy(() -> upgrade()).isInstanceOf(SSLPeerUnverifiedException.class);
		assertThat(upgrade()).isSameAs(upgraded);

		assertThat(recorder.lastFailure()).isNull();
	}

	@Test
	void recordsOnlyCertificateAndHandshakeFailures() {
		// 그 밖의 전송 오류는 원래 분류(연결 끊김 등)를 따름
		outcomes.add(new IOException("Connection reset"));

		assertThatThrownBy(() -> upgrade()).hasMessage("Connection reset");
		assertThat(recorder.lastFailure()).isNull();
	}

	@Test
	void forgetsEverythingWhenCleared() {
		outcomes.add(new SSLHandshakeException("handshake_failure"));
		assertThatThrownBy(() -> upgrade()).isInstanceOf(SSLHandshakeException.class);

		recorder.clear();

		assertThat(recorder.lastFailure()).isNull();
	}

	private SSLSocket upgrade() throws IOException {
		return recorder.upgrade(new Socket(), "api.example", 443, null, null);
	}

}
