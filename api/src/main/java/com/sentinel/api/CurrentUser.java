package com.sentinel.api;

import com.sentinel.application.auth.AuthenticatedUser;
import com.sentinel.application.auth.ForbiddenException;
import com.sentinel.application.auth.UnauthenticatedException;
import com.sentinel.infra.security.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 현재 요청의 사용자. {@link AuthFilter}가 요청 속성에 담아 둔 값을 읽음.
 */
@Component
@RequiredArgsConstructor
class CurrentUser {

	private final HttpServletRequest request;

	AuthenticatedUser get() {
		Object user = request.getAttribute(AuthFilter.USER_ATTRIBUTE);
		if (user instanceof AuthenticatedUser authenticated) {
			return authenticated;
		}
		throw new UnauthenticatedException();
	}

	/** 관리자 전용 기능 확인 */
	AuthenticatedUser requireAdmin() {
		AuthenticatedUser user = get();
		if (!user.isAdmin()) {
			throw new ForbiddenException("administrator role is required");
		}
		return user;
	}

}
