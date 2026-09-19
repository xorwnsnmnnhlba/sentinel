package com.sentinel.application;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 페이지 응답. Spring Data Page의 JSON 직렬화 형식에 API가 의존하지 않도록 별도 정의.
 * @param page 0부터 시작
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	static <T> PageResponse<T> of(Page<?> page, List<T> content) {
		return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
