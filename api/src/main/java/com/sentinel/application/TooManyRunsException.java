package com.sentinel.application;

/**
 * 동시에 진행 중인 수동 실행이 한도에 도달. 실행을 만들지 않음
 */
public class TooManyRunsException extends RuntimeException {

	public TooManyRunsException(int maxConcurrent) {
		super(maxConcurrent + " manual runs are already in progress; try again after one finishes");
	}

}
