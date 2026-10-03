package com.sentinel.application.auth;

import com.sentinel.domain.User;
import com.sentinel.infra.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 관리자 계정 생성. 사용자가 한 명도 없을 때만 동작하며 이후 기동에서는 아무것도 하지 않음.
 * <p>
 * 비밀번호는 환경변수로 주입하고 로그에 남기지 않음. 첫 로그인 후 변경 권장.
 */
@Component
@RequiredArgsConstructor
class AdminBootstrap {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository users;

	private final AuthService authService;

	private final AuthProperties properties;

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void createAdminIfNoUsers() {
		if (users.countByArchivedAtIsNull() > 0) {
			return;
		}
		if (!properties.hasAdmin()) {
			log.warn("No users exist and no initial administrator is configured "
					+ "(sentinel.auth.admin-email, sentinel.auth.admin-password)");
			return;
		}
		User admin = new User(properties.adminEmail(), "관리자", authService.encodePassword(properties.adminPassword()),
				User.Role.ADMIN);
		users.save(admin);
		log.info("Created the initial administrator account for {}", admin.getEmail());
	}

}
