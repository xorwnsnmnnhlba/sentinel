package com.sentinel.application.secret;

import java.util.Optional;

/**
 * 시크릿 암·복호화. 키 미설정 시 {@link SecretKeyMissingException}으로 저장·사용 모두 차단.
 * <p>
 * 키 교체 중(이전 키 설정)에는 이전 키로 저장된 값도 복호화. 암호화는 항상 현재 키.
 */
public interface SecretCipher {

	/** @return 저장용 암호문 */
	String encrypt(String plainText);

	/** @throws SecretDecryptionException 키 불일치·손상 */
	String decrypt(String cipherText);

	/**
	 * 이전 키로 저장된 값을 현재 키로 다시 암호화
	 * @return 다시 암호화한 값. 이미 현재 키로 저장된 값이면 빈 값
	 * @throws SecretDecryptionException 어느 키로도 복호화할 수 없음
	 */
	Optional<String> reEncrypt(String cipherText);

	/** 키 설정 여부. 미설정 시 시크릿 등록 거부 */
	boolean isEnabled();

}
