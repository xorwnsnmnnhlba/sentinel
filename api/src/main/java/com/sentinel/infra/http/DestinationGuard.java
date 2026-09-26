package com.sentinel.infra.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

import org.apache.hc.client5.http.DnsResolver;

/**
 * 전송하지 않을 목적지 확인.
 * <p>
 * 누구나 환경의 기본 URL을 정할 수 있고 응답 본문이 실행 이력에 남으므로, 클라우드 인스턴스 메타데이터(인스턴스 자격증명)를
 * 읽는 통로가 되지 않도록 링크 로컬 주소와 알려진 메타데이터 주소를 막음. 사설망(10.x, 192.168.x 등)은 내부 API 점검이 목적이라 허용.
 * <p>
 * HTTP 클라이언트의 주소 해석기로 동작. 연결 직전에 한 번 해석한 주소를 확인하고 그 주소로만 연결하므로, 확인과 연결 사이에
 * DNS 응답을 바꾸는 공격(DNS rebinding)도 통하지 않음. IP를 직접 적은 주소도 같은 경로로 확인
 */
final class DestinationGuard implements DnsResolver {

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
	 * @return 연결할 주소. 하나라도 막힌 주소면 {@link BlockedDestinationException}
	 */
	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		// IPv6 리터럴은 [ ]로 감싸져 올 수 있음
		String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		InetAddress[] addresses = resolver.resolve(bare);
		for (InetAddress address : addresses) {
			if (address.isLinkLocalAddress() || METADATA_ADDRESSES.contains(address.getHostAddress())) {
				throw new BlockedDestinationException("%s resolves to %s, a link-local or cloud metadata address"
					.formatted(bare, address.getHostAddress()));
			}
		}
		return addresses;
	}

	/**
	 * Kerberos 등 인증 방식에서만 쓰이며 실행기는 미사용. 확인만 거친 뒤 이름 그대로 반환
	 */
	@Override
	public String resolveCanonicalHostname(String host) throws UnknownHostException {
		resolve(host);
		return host;
	}

}
