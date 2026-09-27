package com.sentinel.application.drift;

import java.util.List;

import com.sentinel.runner.schema.SchemaDrift;

/**
 * 결과 1건의 구조 변화. 기준선은 같은 케이스·환경의 이전 실행 지문
 *
 * @param status 판정 여부. 이전 실행이 최소 관측 수보다 적으면 COLLECTING (changes 빈 목록)
 * @param observations 기준선에 쓴 이전 실행 수
 * @param changes 변화. 등급(심한 순) → 경로 순
 */
public record ResultDrift(Status status, int observations, List<SchemaDrift.Change> changes) {

	public enum Status {
		COMPARED, COLLECTING
	}

	public ResultDrift {
		changes = List.copyOf(changes);
	}

}
