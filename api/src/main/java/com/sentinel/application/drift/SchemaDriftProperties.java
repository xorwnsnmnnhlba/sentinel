package com.sentinel.application.drift;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 응답 구조 변화 판정 기준. 실제 관측치를 보고 조정할 수 있도록 설정으로 분리
 *
 * @param window 기준선에 쓰는 이전 실행 수 (같은 케이스·환경의 최근 실행)
 * @param minObservations 판정에 필요한 최소 이전 실행 수. 미만이면 기준선 수집 중
 */
@ConfigurationProperties("sentinel.schema-drift")
public record SchemaDriftProperties(
		@DefaultValue("20") int window,
		@DefaultValue("3") int minObservations) {

	public SchemaDriftProperties {
		if (minObservations < 1 || window < minObservations) {
			throw new IllegalArgumentException("sentinel.schema-drift requires 1 <= min-observations <= window but was "
					+ minObservations + " and " + window);
		}
	}

}
