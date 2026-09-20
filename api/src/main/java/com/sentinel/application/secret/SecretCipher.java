package com.sentinel.application.secret;

/**
 * 시크릿 암·복호화. 키 미설정 시 {@link SecretKeyMissingException}으로 저장·사용 모두 차단.
 */
public interface SecretCipher {

	/** @return 저장용 암호문 */
	String encrypt(String plainText);

	/** @throws SecretDecryptionException 키 불일치·손상 */
	String decrypt(String cipherText);

	/** 키 설정 여부. 미설정 시 시크릿 등록 거부 */
	boolean isEnabled();

}
