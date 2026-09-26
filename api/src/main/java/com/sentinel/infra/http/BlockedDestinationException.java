package com.sentinel.infra.http;

import java.net.UnknownHostException;

/**
 * 목적지가 전송하지 않을 주소로 해석됨. HTTP 클라이언트의 주소 해석 단계({@code DnsResolver})가 던질 수 있는 예외가
 * {@link UnknownHostException}뿐이라 그 하위 타입으로 정의하고, 실행기에서 일반 해석 실패와 구분
 */
final class BlockedDestinationException extends UnknownHostException {

	BlockedDestinationException(String message) {
		super(message);
	}

}
