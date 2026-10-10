package com.sentinel.api;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서 (OpenAPI, {@code GET /api/openapi}). 경로·요청·응답은 컨트롤러에서, 설명은 컨트롤러 Javadoc에서 생성
 * <p>
 * 모든 API는 Bearer 토큰(로그인 세션 토큰 또는 API 토큰) 필요. 로그인 전에 쓰는 요청은 {@code @SecurityRequirements}로 표시
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	static final String BEARER = "bearer";

	/** 문서의 묶음 순서와 설명. 이름은 컨트롤러의 {@code @Tag}와 같음 */
	static final List<Tag> TAGS = List.of(tag("로그인", "세션 토큰 발급·확인·종료"),
			tag("프로젝트", "프로젝트와 프로젝트별 알림 Webhook"), tag("환경", "기본 URL·변수·시크릿"), tag("스위트", "케이스 묶음과 실행 순서"),
			tag("케이스", "요청 정의·판정 규칙·값 추출"), tag("케이스 가져오기", "OpenAPI 3·Postman 문서에서 케이스 후보 만들기·등록"),
			tag("실행", "스위트·케이스 실행과 결과. API 토큰으로 시작·조회 가능"), tag("스케줄", "정기 실행"),
			tag("점검 시간", "환경 점검 중 스케줄 건너뛰기"), tag("휴일", "휴일 건너뛰기를 켠 스케줄을 실행하지 않는 날"),
			tag("알림", "발송 이력·알림 채널·연결 확인"), tag("통계", "대시보드 집계"), tag("변경 이력", "누가 언제 무엇을 바꿨는지"),
			tag("API 토큰", "CI 등 자동화용 토큰"), tag("사용자", "계정·비밀번호"), tag("초대", "초대 링크 발급·확인"),
			tag("비밀번호 재설정", "재설정 링크 발급·확인"), tag("시스템", "서버 설정·버전 상태 (관리자)"));

	@Bean
	OpenAPI sentinelOpenApi() {
		return new OpenAPI()
			.info(new Info().title("Sentinel API")
				.version("v1")
				.description("""
						사내 API 모니터링 도구 Sentinel의 REST API.
						모든 리소스는 최상위 컬렉션이고 관계는 조회 조건(?projectId=)으로 좁힘. \
						응답에서 다른 리소스는 {id, name} 객체로 가리킴. 오류는 ProblemDetail(detail에 사유).
						API 토큰(snt_)은 실행 시작(POST /api/runs)과 그 결과 조회(GET /api/runs/{id})만 가능."""))
			.components(new Components().addSecuritySchemes(BEARER,
					new SecurityScheme().type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.description("로그인 세션 토큰(POST /api/sessions) 또는 API 토큰(snt_)")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER))
			.tags(TAGS);
	}

	private static Tag tag(String name, String description) {
		return new Tag().name(name).description(description);
	}

}
