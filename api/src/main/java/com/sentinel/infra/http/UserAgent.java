package com.sentinel.infra.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Sentinel이 보내는 HTTP 요청(실행기·Webhook·생존 신호)의 기본 User-Agent. 케이스에 직접 적은 값이 있으면 그 값을 보냄.
 * <p>
 * HTTP 라이브러리 기본값(예: {@code Apache-HttpClient/5.6.4 (Java/25.0.4.1)})은 보안 장비가 자동화 도구로 보고 막는 경우가 많고
 * 감시 대상 서버에 라이브러리·Java 버전을 알려 줌. 서버 운영자가 로그에서 알아볼 수 있는 이름으로 보냄
 */
public final class UserAgent {

	/** 예: {@code Sentinel/1.0.0}. 빌드 정보가 없으면(개발 실행) {@code Sentinel/dev} */
	public static final String VALUE = "Sentinel/" + version();

	private UserAgent() {
	}

	private static String version() {
		try (InputStream in = UserAgent.class.getResourceAsStream("/META-INF/build-info.properties")) {
			if (in == null) {
				return "dev";
			}
			Properties properties = new Properties();
			properties.load(in);
			return properties.getProperty("build.version", "dev");
		}
		catch (IOException ex) {
			return "dev";
		}
	}

}
