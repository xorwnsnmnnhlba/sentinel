package com.sentinel.infra.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;

/**
 * 전송하지 않을 목적지 확인.
 * <p>
 * 누구나 환경의 기본 URL을 정할 수 있고 응답 본문이 실행 이력에 남으므로, 클라우드 인스턴스 메타데이터(인스턴스 자격증명)를
 * 읽는 통로가 되지 않도록 링크 로컬 주소와 알려진 메타데이터 주소를 막음. 사설망(10.x, 192.168.x 등)은 내부 API 점검이 목적이라 허용.
 * <p>
 * 호스트 이름은 실제로 해석한 주소로 판단 (예: metadata.google.internal → 169.254.169.254).
 * 확인 후 전송 사이에 DNS 응답이 바뀌는 경우(DNS rebinding)까지는 막지 못함
 */
final class DestinationGuard {

	/** 링크 로컬 대역 밖의 메타데이터 주소. AWS IPv6, Alibaba Cloud */
	private static final Set<String> METADATA_ADDRESSES = Set.of("fd00:ec2:0:0:0:0:0:254", "100.100.100.200");

	@FunctionalInterface
	interface Resolver {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	private final Resolver resolver;

	DestinationGuard(Resolver resolver) {
		this.resolver = resolver;
	}

	static DestinationGuard system() {
		return new DestinationGuard(InetAddress::getAllByName);
	}

	/**
	 * @return 막은 이유. 허용이면 빈 값 (해석되지 않는 호스트도 빈 값, 전송 단계에서 연결 실패로 처리)
	 */
	Optional<String> blockedReason(URI uri) {
		String host = uri.getHost();
		if (host == null) {
			return Optional.empty();
		}
		// IPv6 리터럴은 [ ]로 감싸져 있음
		String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		InetAddress[] addresses;
		try {
			addresses = resolver.resolve(bare);
		}
		catch (UnknownHostException ex) {
			return Optional.empty();
		}
		for (InetAddress address : addresses) {
			if (address.isLinkLocalAddress() || METADATA_ADDRESSES.contains(address.getHostAddress())) {
				return Optional.of("%s resolves to %s, a link-local or cloud metadata address".formatted(host,
						address.getHostAddress()));
			}
		}
		return Optional.empty();
	}

}
