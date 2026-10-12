package com.sentinel.api;

import com.sentinel.domain.TestSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerConcurrentModificationTest {

	@Test
	void answersAConcurrentModificationWith409AndAFixedDetail() {
		ProblemDetail problem = new ApiExceptionHandler()
			.handleOptimisticLock(new ObjectOptimisticLockingFailureException(TestSchedule.class, 1L));

		assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		// web(lib/actions)이 이 문구로 이름 중복(409)과 구분
		assertThat(problem.getDetail()).isEqualTo("modified by another request; reload and try again");
	}

}
