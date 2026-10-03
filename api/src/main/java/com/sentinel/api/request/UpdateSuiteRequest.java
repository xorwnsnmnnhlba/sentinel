package com.sentinel.api.request;

import java.util.List;

/**
 * 스위트 부분 수정. null 필드는 변경하지 않음, description을 빈 문자열로 지정하면 제거.
 *
 * @param caseOrder 케이스 실행 순서. 스위트의 사용 중인 케이스 ID 전체를 실행할 순서대로
 */
public record UpdateSuiteRequest(String name, String description, List<Long> caseOrder) {
}
