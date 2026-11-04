package com.giaokhoa.server.math;

import java.security.SecureRandom;
import java.util.UUID;

// Sinh định danh UUIDv7 theo RFC 9562: 48 bit thời gian mili giây, trường version 7, trường variant của RFC
// và phần còn lại là bit ngẫu nhiên. Mọi định danh mới của lát này dùng đúng dạng này.
final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	static UUID generate() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		long millis = System.currentTimeMillis();
		for (int i = 5; i >= 0; i--) {
			bytes[i] = (byte) (millis & 0xFF);
			millis >>= 8;
		}
		bytes[6] = (byte) (0x70 | (bytes[6] & 0x0F));
		bytes[8] = (byte) (0x80 | (bytes[8] & 0x3F));
		long mostSig = 0;
		long leastSig = 0;
		for (int i = 0; i < 8; i++) {
			mostSig = (mostSig << 8) | (bytes[i] & 0xFFL);
		}
		for (int i = 8; i < 16; i++) {
			leastSig = (leastSig << 8) | (bytes[i] & 0xFFL);
		}
		return new UUID(mostSig, leastSig);
	}

}
