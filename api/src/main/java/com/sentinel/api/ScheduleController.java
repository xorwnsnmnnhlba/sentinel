package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.application.ScheduleDetail;
import com.sentinel.application.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
class ScheduleController {

	private final ScheduleService scheduleService;

	ScheduleController(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@GetMapping
	List<ScheduleDetail> list(@RequestParam(required = false) Long suiteId) {
		return scheduleService.list(suiteId);
	}

	@GetMapping("/{scheduleId}")
	ScheduleDetail get(@PathVariable long scheduleId) {
		return scheduleService.get(scheduleId);
	}

	@PostMapping
	ResponseEntity<ScheduleDetail> create(@Valid @RequestBody CreateScheduleRequest request) {
		ScheduleDetail schedule = scheduleService.create(request.suiteId(), request.environmentId(), request.cron());
		return ResponseEntity.created(URI.create("/api/schedules/" + schedule.id())).body(schedule);
	}

	/**
	 * 부분 수정. 현재 enabled만 지원, 생략한 필드는 유지
	 */
	@PatchMapping("/{scheduleId}")
	ScheduleDetail update(@PathVariable long scheduleId, @RequestBody UpdateScheduleRequest request) {
		return scheduleService.update(scheduleId, request.enabled());
	}

}
