package com.sentinel.application.secret;

import com.sentinel.application.Transactions;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 암호화 키 미설정이면 저장된 값을 확인하지 않음. 키를 뺀 채 기동해도 남아 있는 암호문 때문에 기동이 실패하지 않도록
 */
class SecretKeyRotationDisabledTest {

	@Test
	void leavesStoredValuesAloneWithoutAKey() {
		SecretCipher cipher = mock(SecretCipher.class);
		when(cipher.isEnabled()).thenReturn(false);
		ProjectRepository projects = mock(ProjectRepository.class);
		EnvironmentRepository environments = mock(EnvironmentRepository.class);
		Transactions transactions = mock(Transactions.class);

		new SecretKeyRotation(cipher, projects, environments, transactions).afterSingletonsInstantiated();

		verifyNoInteractions(projects, environments, transactions);
	}

}
