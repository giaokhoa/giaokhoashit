package com.giaokhoa.server.files;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Sinh UUID phiên bản 7 theo RFC 9562: 48 bit thời gian mili giây ở đầu nên định danh có thứ tự theo thời gian
 * tạo, phần còn lại là ngẫu nhiên cùng các bit phiên bản và biến thể chuẩn. Mọi định danh mới của nền tệp dùng
 * bộ sinh này.
 */
public final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	public static UUID next() {
		long timestamp = System.currentTimeMillis();
		byte[] random = new byte[10];
		RANDOM.nextBytes(random);
		long mostSignificant = (timestamp << 16) | 0x7000L | ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);
		long leastSignificant = ((random[2] & 0x3FL) | 0x80L) << 56;
		for (int i = 3; i < 10; i++) {
			leastSignificant |= (random[i] & 0xFFL) << (8 * (9 - i));
		}
		return new UUID(mostSignificant, leastSignificant);
	}

}
