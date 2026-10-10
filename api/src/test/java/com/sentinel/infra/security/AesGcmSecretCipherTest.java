package com.sentinel.infra.security;

import java.util.Base64;
import java.util.Optional;

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

	private static final String PREVIOUS_KEY = Base64.getEncoder().encodeToString("fedcba9876543210".getBytes());

	private static final String OTHER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());

	private final SecretCipher cipher = new AesGcmSecretCipher(KEY);

	private final SecretCipher previous = new AesGcmSecretCipher(PREVIOUS_KEY);

	private final SecretCipher rotating = new AesGcmSecretCipher(KEY, PREVIOUS_KEY);

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
		String encrypted = new AesGcmSecretCipher(OTHER_KEY).encrypt("token");

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
		SecretCipher disabled = new SecretCipherConfig().secretCipher(new SecretProperties(" ", null));

		assertThat(disabled.isEnabled()).isFalse();
		assertThatThrownBy(() -> disabled.encrypt("token")).isInstanceOf(SecretKeyMissingException.class);
		assertThatThrownBy(() -> disabled.decrypt("v1:abc")).isInstanceOf(SecretKeyMissingException.class);
		assertThat(new SecretCipherConfig().secretCipher(new SecretProperties(KEY, " ")).isEnabled()).isTrue();
		assertThatThrownBy(() -> disabled.reEncrypt("v1:abc")).isInstanceOf(SecretKeyMissingException.class);
	}

	@Test
	void decryptsValuesSavedWithThePreviousKeyAndEncryptsWithTheCurrentOne() {
		assertThat(rotating.decrypt(previous.encrypt("old-token"))).isEqualTo("old-token");
		assertThat(rotating.decrypt(cipher.encrypt("new-token"))).isEqualTo("new-token");
		assertThat(cipher.decrypt(rotating.encrypt("token"))).isEqualTo("token");
		assertThatThrownBy(() -> previous.decrypt(rotating.encrypt("token")))
			.isInstanceOf(SecretDecryptionException.class);
	}

	@Test
	void stillRejectsValuesSavedWithNeitherKey() {
		String encrypted = new AesGcmSecretCipher(OTHER_KEY).encrypt("token");

		assertThatThrownBy(() -> rotating.decrypt(encrypted)).isInstanceOf(SecretDecryptionException.class)
			.hasMessageContaining("key changed or value corrupted");
		assertThatThrownBy(() -> rotating.reEncrypt(encrypted)).isInstanceOf(SecretDecryptionException.class);
	}

	@Test
	void reEncryptsOnlyValuesSavedWithThePreviousKey() {
		Optional<String> rotated = rotating.reEncrypt(previous.encrypt("old-token"));

		assertThat(rotated).isPresent();
		assertThat(cipher.decrypt(rotated.get())).isEqualTo("old-token");
		assertThat(rotating.reEncrypt(cipher.encrypt("new-token"))).isEmpty();
	}

	@Test
	void reEncryptsNothingWithoutAPreviousKey() {
		assertThat(cipher.reEncrypt(cipher.encrypt("token"))).isEmpty();
		assertThatThrownBy(() -> cipher.reEncrypt(previous.encrypt("token")))
			.isInstanceOf(SecretDecryptionException.class);
	}

	@Test
	void namesThePreviousKeyWhenItIsInvalid() {
		assertThatThrownBy(() -> new AesGcmSecretCipher(KEY, Base64.getEncoder().encodeToString(new byte[20])))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sentinel.secret.previous-key")
			.hasMessageContaining("16, 24 or 32 bytes");
	}

	@Test
	void acceptsThePreviousKeyOnlyTogetherWithACurrentKey() {
		SecretCipherConfig config = new SecretCipherConfig();

		assertThat(config.secretCipher(new SecretProperties(KEY, PREVIOUS_KEY)).decrypt(previous.encrypt("token")))
			.isEqualTo("token");
		assertThatThrownBy(() -> config.secretCipher(new SecretProperties(null, PREVIOUS_KEY)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("sentinel.secret.key");
		// 같은 키를 이전 키로 넣으면 무시 (교체할 것이 없음)
		assertThat(config.secretCipher(new SecretProperties(KEY, KEY)).reEncrypt(cipher.encrypt("token"))).isEmpty();
	}

}
