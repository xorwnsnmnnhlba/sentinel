package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateEnvironmentRequest;
import com.sentinel.api.request.UpdateEnvironmentRequest;
import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.EnvironmentItem;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "환경")
@RequestMapping("/api/environments")
@RequiredArgsConstructor
class EnvironmentController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	private final CurrentUser currentUser;

	/** 환경 목록, 이름 순. projectId로 좁힘. 시크릿은 이름만 */
	@GetMapping
	List<EnvironmentItem> list(@RequestParam(required = false) Long projectId) {
		return catalog.listEnvironments(projectId);
	}

	/** 단건. 변수 값 포함, 시크릿은 이름만 */
	@GetMapping("/{environmentId}")
	EnvironmentItem get(@PathVariable long environmentId) {
		return catalog.getEnvironment(environmentId);
	}

	/**
	 * 등록. 201 + Location, 같은 부모 아래 사용 중인 이름이면 409
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping
	ResponseEntity<EnvironmentItem> create(@Valid @RequestBody CreateEnvironmentRequest request) {
		EnvironmentItem created = commands.createEnvironment(request.projectId(), request.name(), request.baseUrl(), request.variables(),
				request.secrets(), currentUser.get().id());
		return ResponseEntity.created(URI.create("/api/environments/" + created.id())).body(created);
	}

	/**
	 * 부분 수정. 생략한 필드는 유지
	 */
	@PatchMapping("/{environmentId}")
	EnvironmentItem update(@PathVariable long environmentId, @RequestBody UpdateEnvironmentRequest request) {
		return commands.updateEnvironment(environmentId, request.name(), request.baseUrl(), request.variables(),
				request.secrets(), currentUser.get().id());
	}

	/**
	 * 삭제(보관). 하위 항목 연쇄 보관, 실행 이력은 유지
	 */
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@DeleteMapping("/{environmentId}")
	ResponseEntity<Void> delete(@PathVariable long environmentId) {
		commands.archiveEnvironment(environmentId, currentUser.get().id());
		return ResponseEntity.noContent().build();
	}

}
