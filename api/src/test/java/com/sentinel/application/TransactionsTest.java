package com.sentinel.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionsTest {

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private final Transactions transactions = new Transactions(transactionManager);

	@Test
	void startsReadOnlyTransactionOnlyForReadOnly() {
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

		String read = transactions.readOnly(status -> "read");
		String written = transactions.write(status -> "written");
		transactions.writeWithoutResult(status -> {
		});

		ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(transactionManager, times(3)).getTransaction(definitions.capture());
		assertThat(definitions.getAllValues()).extracting(TransactionDefinition::isReadOnly)
			.containsExactly(true, false, false);
		assertThat(read).isEqualTo("read");
		assertThat(written).isEqualTo("written");
	}

}
