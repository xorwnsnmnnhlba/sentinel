package com.sentinel.infra.security;

import java.util.Base64;

import com.sentinel.application.secret.SecretCipher;
import com.sentinel.application.secret.SecretDecryptionException;
import com.sentinel.application.secret.SecretKeyMissingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSecretCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private final SecretCipher cipher = new AesGcmSecretCipher(KEY);

	@Test
	void restoresTheOriginalValue() {
		String token = "Bearer 매우-긴-운영-토큰-값";

		String encrypted = cipher.encrypt(token);

		assertThat(encrypted).startsWith("v1:").doesNotContain(token);
		assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
	}

	@Test
	void producesADifferentCipherTextEveryTime() {
		// GCM은 같은 키·IV를 재사용하면 평문이 드러나므로 암호화마다 IV를 새로 생성
		assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
	}

	@Test
	void rejectsValuesEncryptedWithAnotherKey() {
		String encrypted = new AesGcmSecretCipher(Base64.getEncoder().encodeToString("0123456789abcdef".getBytes()))
			.encrypt("token");

		assertThatThrownBy(() -> cipher.decrypt(encrypted)).isInstanceOf(SecretDecryptionException.class)
			.hasMessageContaining("key changed or value corrupted");
	}

	@Test
	void rejectsTamperedValues() {
		String encrypted = cipher.encrypt("token");
		String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(SecretDecryptionException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "plain", "v2:abc", "" })
	void rejectsValuesThatAreNotInTheStoredFormat(String value) {
		assertThatThrownBy(() -> cipher.decrypt(value)).isInstanceOf(SecretDecryptionException.class);
	}

	@Test
	void rejectsKeysOfTheWrongLength() {
		assertThatThrownBy(() -> new AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[20])))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("16, 24 or 32 bytes");
		assertThatThrownBy(() -> new AesGcmSecretCipher("not base64!"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Base64");
	}

	@Test
	void refusesEverythingWhenNoKeyIsConfigured() {
		SecretCipher disabled = new SecretCipherConfig().secretCipher(new SecretProperties(" "));

		assertThat(disabled.isEnabled()).isFalse();
		assertThatThrownBy(() -> disabled.encrypt("token")).isInstanceOf(SecretKeyMissingException.class);
		assertThatThrownBy(() -> disabled.decrypt("v1:abc")).isInstanceOf(SecretKeyMissingException.class);
		assertThat(new SecretCipherConfig().secretCipher(new SecretProperties(KEY)).isEnabled()).isTrue();
	}

}
