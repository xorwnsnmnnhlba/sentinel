package com.sentinel.application;

import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 프로그래밍 방식 트랜잭션. 한 메서드 안에서 트랜잭션을 여러 번 나누는 곳(실행 준비·케이스별 저장·완료 기록, 스케줄 폴링)에서 사용.
 * <p>
 * 트랜잭션 구성을 이 빈에 모아 사용하는 쪽 생성자는 필드 대입만 하도록 유지 ({@code @RequiredArgsConstructor}).
 */
@Component
public class Transactions {

	private final TransactionTemplate write;

	private final TransactionTemplate readOnly;

	private final TransactionTemplate separate;

	Transactions(PlatformTransactionManager transactionManager) {
		this.write = new TransactionTemplate(transactionManager);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.separate = new TransactionTemplate(transactionManager);
		this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public <T> T write(TransactionCallback<T> action) {
		return write.execute(action);
	}

	public void writeWithoutResult(Consumer<TransactionStatus> action) {
		write.executeWithoutResult(action);
	}

	public <T> T readOnly(TransactionCallback<T> action) {
		return readOnly.execute(action);
	}

	/** 호출한 쪽 트랜잭션과 별도로 커밋 (부가 기록의 실패가 본래 작업을 되돌리지 않도록) */
	public <T> T writeSeparately(TransactionCallback<T> action) {
		return separate.execute(action);
	}

}
