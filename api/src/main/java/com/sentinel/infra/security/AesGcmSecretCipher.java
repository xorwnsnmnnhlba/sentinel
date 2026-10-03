package com.sentinel.infra.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import com.sentinel.application.secret.SecretCipher;
import com.sentinel.application.secret.SecretDecryptionException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM 암·복호화. 저장 형식은 {@code v1:Base64(IV+암호문+태그)}.
 * <p>
 * GCM은 같은 키·IV 재사용 시 평문이 드러나므로 암호화마다 12바이트 IV를 새로 생성.
 * 접두어 {@code v1}은 형식 변경(키 교체·알고리즘 변경) 시 구분용.
 */
class AesGcmSecretCipher implements SecretCipher {

	private static final String PREFIX = "v1:";

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int IV_LENGTH = 12;

	private static final int TAG_LENGTH_BITS = 128;

	private final SecretKey key;

	private final SecureRandom random = new SecureRandom();

	/**
	 * @param base64Key Base64 인코딩한 16·24·32바이트 키
	 */
	public AesGcmSecretCipher(String base64Key) {
		byte[] keyBytes;
		try {
			keyBytes = Base64.getDecoder().decode(base64Key.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("sentinel.secret.key must be Base64 encoded", ex);
		}
		if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
			throw new IllegalArgumentException(
					"sentinel.secret.key must decode to 16, 24 or 32 bytes but was " + keyBytes.length);
		}
		this.key = new SecretKeySpec(keyBytes, "AES");
	}

	@Override
	public String encrypt(String plainText) {
		byte[] iv = new byte[IV_LENGTH];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
			byte[] combined = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
			return PREFIX + Base64.getEncoder().encodeToString(combined);
		}
		catch (GeneralSecurityException ex) {
			// 메시지에 평문이 들어가지 않도록 원인만 전달
			throw new IllegalStateException("failed to encrypt a secret", ex);
		}
	}

	@Override
	public String decrypt(String cipherText) {
		if (cipherText == null || !cipherText.startsWith(PREFIX)) {
			throw new SecretDecryptionException("stored secret is not in the expected format", null);
		}
		try {
			byte[] combined = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
			if (combined.length <= IV_LENGTH) {
				throw new SecretDecryptionException("stored secret is too short", null);
			}
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(TAG_LENGTH_BITS, Arrays.copyOf(combined, IV_LENGTH)));
			byte[] decrypted = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
			return new String(decrypted, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new SecretDecryptionException("failed to decrypt a secret (key changed or value corrupted)", ex);
		}
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

}
