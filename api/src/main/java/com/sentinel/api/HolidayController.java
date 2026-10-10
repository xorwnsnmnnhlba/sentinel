package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateHolidayImportRequest;
import com.sentinel.api.request.CreateHolidayRequest;
import com.sentinel.application.HolidayService;
import com.sentinel.application.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "휴일")
@RequestMapping("/api")
@RequiredArgsConstructor
class HolidayController {

	private final HolidayService holidayService;

	private final CurrentUser currentUser;

	/** 그해 휴일, 날짜 순. 생략 시 올해 */
	@GetMapping("/holidays")
	List<HolidayService.HolidayItem> list(@RequestParam(required = false) Integer year) {
		return holidayService.list(year);
	}

	/** 단건 */
	@GetMapping("/holidays/{holidayId}")
	HolidayService.HolidayItem get(@PathVariable long holidayId) {
		return holidayService.get(holidayId);
	}

	/** 등록 (관리자). 이미 등록한 날짜는 409 */
	@PostMapping("/holidays")
	ResponseEntity<HolidayService.HolidayItem> create(@Valid @RequestBody CreateHolidayRequest request) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		HolidayService.HolidayItem created = holidayService.create(request.date(), request.name(), admin.id());
		return ResponseEntity.created(URI.create("/api/holidays/" + created.id())).body(created);
	}

	/**
	 * 여러 휴일을 한 번에 등록. 이미 등록한 날짜는 건너뛰고 {@code skipped}로 알림. 한 건이라도 잘못되면 전부 미등록
	 */
	@PostMapping("/holiday-imports")
	@ResponseStatus(HttpStatus.CREATED)
	HolidayService.HolidayImport importHolidays(@RequestBody CreateHolidayImportRequest request) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		return holidayService.importHolidays(request.holidays(), admin.id());
	}

	/** 삭제 (관리자). 그날도 스케줄 실행 */
	@DeleteMapping("/holidays/{holidayId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long holidayId) {
		AuthenticatedUser admin = currentUser.requireAdmin();
		holidayService.delete(holidayId, admin.id());
	}

}
