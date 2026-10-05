package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateHolidayRequest;
import com.sentinel.application.HolidayService;
import com.sentinel.application.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 휴일. "휴일 건너뛰기"를 켠 스케줄은 이 날짜에 실행하지 않음. 모든 스케줄에 영향을 주므로 등록·삭제는 관리자만
 */
@RestController
@RequestMapping("/api/holidays")
@RequiredArgsConstructor
class HolidayController {

	private final HolidayService holidayService;

	private final CurrentUser currentUser;

	/** 그해 휴일, 날짜 순. 생략 시 올해 */
	@GetMapping
	List<HolidayService.HolidayItem> list(@RequestParam(required = false) Integer year) {
		return holidayService.list(year);
	}

	@PostMapping
	ResponseEntity<HolidayService.HolidayItem> create(@Valid @RequestBody CreateHolidayRequest request) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		HolidayService.HolidayItem created = holidayService.create(request.date(), request.name(), admin.id());
		return ResponseEntity.created(URI.create("/api/holidays/" + created.id())).body(created);
	}

	@DeleteMapping("/{holidayId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long holidayId) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		holidayService.delete(holidayId, admin.id());
	}

}
