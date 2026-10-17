package com.sentinel.infra.http;

import java.io.IOException;
import java.net.Socket;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * TLS 연결 수립을 감싸 인증서·TLS 실패를 기록. 동작은 감싼 전략 그대로.
 * <p>
 * 이름에 주소가 여럿(IPv4·IPv6)이면 HTTP 클라이언트가 TLS 실패 뒤 다음 주소로 다시 연결하고 마지막 실패만 남기므로, 다음 주소가 닿지
 * 않으면 인증서 오류가 연결 실패에 가려짐 ({@link RestClientRequestExecutor}가 이 기록으로 바로잡음). 다음 주소의 TLS가 성공하면 지움.
 * 연결·TLS는 요청을 보낸 스레드에서 진행하므로 스레드별로 기록
 */
final class TlsFailureRecorder implements TlsSocketStrategy {

	private final TlsSocketStrategy delegate;

	private final ThreadLocal<IOException> lastFailure = new ThreadLocal<>();

	TlsFailureRecorder(TlsSocketStrategy delegate) {
		this.delegate = delegate;
	}

	@Override
	public SSLSocket upgrade(Socket socket, String target, int port, Object attachment, HttpContext context)
			throws IOException {
		try {
			SSLSocket upgraded = delegate.upgrade(socket, target, port, attachment, context);
			lastFailure.remove();
			return upgraded;
		}
		catch (SSLHandshakeException | SSLPeerUnverifiedException ex) {
			lastFailure.set(ex);
			throw ex;
		}
	}

	/** 이 스레드에서 마지막으로 실패한 TLS 연결 수립. 그 뒤 성공했거나 없으면 null */
	IOException lastFailure() {
		return lastFailure.get();
	}

	/** 요청이 끝나면 지움 (같은 스레드의 다음 요청에 남지 않도록) */
	void clear() {
		lastFailure.remove();
	}

}
