package com.sentinel.api;

import java.net.URI;
import java.util.List;

import com.sentinel.api.request.CreateScheduleRequest;
import com.sentinel.api.request.UpdateScheduleRequest;
import com.sentinel.api.request.UpdateSchedulesRequest;
import com.sentinel.application.ScheduleDetail;
import com.sentinel.application.ScheduleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "스케줄")
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
class ScheduleController {

	private final ScheduleService scheduleService;

	private final CurrentUser currentUser;

	/** 스케줄 목록. suiteId로 좁힘 */
	@GetMapping
	List<ScheduleDetail> list(@RequestParam(required = false) Long suiteId) {
		return scheduleService.list(suiteId);
	}

	/** 단건. 다음·마지막 실행 시각 포함 */
	@GetMapping("/{scheduleId}")
	ScheduleDetail get(@PathVariable long scheduleId) {
		return scheduleService.get(scheduleId);
	}

	/** 등록. cron은 5자리(분 시 일 월 요일), 서버 시간대 기준 */
	@PostMapping
	ResponseEntity<ScheduleDetail> create(@Valid @RequestBody CreateScheduleRequest request) {
		ScheduleDetail schedule = scheduleService.create(request.suiteId(), request.environmentId(), request.cron(),
				Boolean.TRUE.equals(request.skipHolidays()), currentUser.get().id());
		return ResponseEntity.created(URI.create("/api/schedules/" + schedule.id())).body(schedule);
	}

	/**
	 * 여러 스케줄의 활성·휴일 건너뛰기를 한 번에 변경. 하나라도 없으면 전부 미변경 (404)
	 */
	@PatchMapping
	List<ScheduleDetail> updateAll(@RequestBody UpdateSchedulesRequest request) {
		return scheduleService.updateAll(request.scheduleIds(), request.enabled(), request.skipHolidays(),
				currentUser.get().id());
	}

	/**
	 * 부분 수정. enabled, cron, environmentId, skipHolidays 지원, 생략한 필드는 유지
	 */
	@PatchMapping("/{scheduleId}")
	ScheduleDetail update(@PathVariable long scheduleId, @RequestBody UpdateScheduleRequest request) {
		return scheduleService.update(scheduleId, request.enabled(), request.cron(), request.environmentId(),
				request.skipHolidays(), currentUser.get().id());
	}

	/**
	 * 삭제(보관). 비활성화되며 실행 이력은 유지
	 */
	@DeleteMapping("/{scheduleId}")
	ResponseEntity<Void> delete(@PathVariable long scheduleId) {
		scheduleService.delete(scheduleId, currentUser.get().id());
		return ResponseEntity.noContent().build();
	}

}
