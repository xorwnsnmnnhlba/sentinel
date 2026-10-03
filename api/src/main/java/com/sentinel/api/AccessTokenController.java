package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateAccessTokenRequest;
import com.sentinel.application.auth.AccessTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 API 토큰. 로그인 세션으로만 사용 (API 토큰으로 토큰을 발급·폐기할 수 없음, {@code AuthFilter})
 */
@RestController
@RequestMapping("/api/access-tokens")
@RequiredArgsConstructor
class AccessTokenController {

	private final AccessTokenService accessTokens;

	private final CurrentUser currentUser;

	/** 발급. 201 + 토큰 원문(이 응답에서만) */
	@PostMapping
	ResponseEntity<AccessTokenService.Issued> create(@Valid @RequestBody CreateAccessTokenRequest request) {
		AccessTokenService.Issued issued = accessTokens.issue(currentUser.get().id(), request.name(),
				request.validityDays());
		return ResponseEntity.created(URI.create("/api/access-tokens/" + issued.accessToken().id())).body(issued);
	}

	/** 폐기되지 않은 내 토큰 (만료 포함). 토큰 원문은 없음 */
	@GetMapping
	List<AccessTokenService.AccessTokenItem> list() {
		return accessTokens.list(currentUser.get().id());
	}

	/** 폐기. 다른 사용자의 토큰은 404 */
	@DeleteMapping("/{tokenId}")
	ResponseEntity<Void> delete(@PathVariable long tokenId) {
		accessTokens.revoke(tokenId, currentUser.get().id());
		return ResponseEntity.noContent().build();
	}

}
