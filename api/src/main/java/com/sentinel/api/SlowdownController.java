package com.sentinel.api;

import java.util.List;

import com.sentinel.application.SlowdownService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class SlowdownController {

	private final SlowdownService slowdowns;

	/**
	 * 지금 느린 케이스·환경 (응답시간 악화 알림 후 아직 회복하지 않음). 감지 당시 늘어난 비율이 큰 순
	 */
	@GetMapping("/api/slowdowns")
	List<SlowdownService.Slowdown> list(@RequestParam(required = false) Long suiteId) {
		return slowdowns.listOpen(suiteId);
	}

}
