package com.sentinel.application.drift;

import java.time.Instant;
import java.util.List;

import com.sentinel.application.IdName;
import com.sentinel.runner.schema.SchemaDrift;

/**
 * 결과 1건의 구조 변화. 기준선은 같은 케이스·환경의 앞선 실행 중 받아들인(승인 대기가 아닌) 지문과 영구 허용
 *
 * @param status 판정 여부. 이전 실행이 최소 관측 수보다 적으면 COLLECTING (changes 빈 목록)
 * @param observations 기준선에 쓴 이전 실행 수 (승인 대기 제외)
 * @param changes 변화. 등급(심한 순) → 경로 순
 * @param pending 승인 대기. 기록할 때 기준선에 넣지 않았고 지금 기준선으로 다시 비교해도 호환이 깨지는 변화·null 전환이 남음
 *     (그사이 같은 변화를 승인했으면 false)
 * @param approval 승인 대기였다가 기준선에 반영한 기록. 기록할 때 바로 받아들인 결과는 null
 */
public record ResultDrift(Status status, int observations, List<SchemaDrift.Change> changes, boolean pending,
		Approval approval) {

	public enum Status {
		COMPARED, COLLECTING
	}

	public ResultDrift {
		changes = List.copyOf(changes);
	}

	/**
	 * @param approvedBy 승인한 사용자. 삭제(보관)된 사용자도 이력 표시를 위해 조회, 찾을 수 없으면 null
	 */
	public record Approval(IdName approvedBy, Instant approvedAt) {
	}

}
