package com.sentinel.application.importing;

import java.util.List;

/**
 * 문서 변환 결과. 저장하지 않음
 *
 * @param servers 문서의 서버 주소. 환경 기본 URL 설정 참고용
 * @param warnings 문서 단위 경고·안내 (지원하지 않아 제외한 작업, 등록할 시크릿 등)
 */
record ConvertedDocument(String title, String version, List<String> servers, List<CaseDraft> cases,
		List<String> warnings) {
}
