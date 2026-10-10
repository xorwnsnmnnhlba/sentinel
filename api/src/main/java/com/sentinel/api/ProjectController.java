package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateProjectRequest;
import com.sentinel.api.request.UpdateProjectRequest;
import com.sentinel.application.CatalogCommandService;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.CatalogQueryService.ProjectItem;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "프로젝트")
@RequestMapping("/api/projects")
@RequiredArgsConstructor
class ProjectController {

	private final CatalogQueryService catalog;

	private final CatalogCommandService commands;

	private final CurrentUser currentUser;

	/** 프로젝트 목록, 이름 순 */
	@GetMapping
	List<ProjectItem> list() {
		return catalog.listProjects();
	}

	/** 단건. Webhook은 지정 여부만 (주소는 응답에 없음) */
	@GetMapping("/{projectId}")
	ProjectItem get(@PathVariable long projectId) {
		return catalog.getProject(projectId);
	}

	/**
	 * 등록. 201 + Location, 같은 부모 아래 사용 중인 이름이면 409
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping
	ResponseEntity<ProjectItem> create(@Valid @RequestBody CreateProjectRequest request) {
		ProjectItem created = commands.createProject(request.name(), request.description(), currentUser.get().id());
		return ResponseEntity.created(URI.create("/api/projects/" + created.id())).body(created);
	}

	/**
	 * 부분 수정. 생략한 필드는 유지
	 */
	@PatchMapping("/{projectId}")
	ProjectItem update(@PathVariable long projectId, @RequestBody UpdateProjectRequest request) {
		return commands.updateProject(projectId, request.name(), request.description(), request.webhookUrl(),
				currentUser.get().id());
	}

	/**
	 * 삭제(보관). 하위 항목 연쇄 보관, 실행 이력은 유지
	 */
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@DeleteMapping("/{projectId}")
	ResponseEntity<Void> delete(@PathVariable long projectId) {
		commands.archiveProject(projectId, currentUser.get().id());
		return ResponseEntity.noContent().build();
	}

}
