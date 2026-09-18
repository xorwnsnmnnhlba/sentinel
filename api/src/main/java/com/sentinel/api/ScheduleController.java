package com.sentinel.api;

import java.net.URI;

import com.sentinel.application.ScheduleDetail;
import com.sentinel.application.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test-schedules")
class ScheduleController {

	private final ScheduleService scheduleService;

	ScheduleController(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@PostMapping
	ResponseEntity<ScheduleDetail> create(@Valid @RequestBody CreateScheduleRequest request) {
		ScheduleDetail schedule = scheduleService.create(request.suiteId(), request.environmentId(), request.cron());
		return ResponseEntity.created(URI.create("/api/test-schedules/" + schedule.id())).body(schedule);
	}

	@GetMapping("/{scheduleId}")
	ScheduleDetail get(@PathVariable long scheduleId) {
		return scheduleService.get(scheduleId);
	}

	@PostMapping("/{scheduleId}/enable")
	ScheduleDetail enable(@PathVariable long scheduleId) {
		return scheduleService.enable(scheduleId);
	}

	@PostMapping("/{scheduleId}/disable")
	ScheduleDetail disable(@PathVariable long scheduleId) {
		return scheduleService.disable(scheduleId);
	}

}
