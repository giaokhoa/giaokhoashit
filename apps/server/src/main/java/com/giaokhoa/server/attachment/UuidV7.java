package com.giaokhoa.server.attachment;

import java.security.SecureRandom;
import java.util.UUID;

// Sinh UUIDv7 (RFC 9562): 48 bit thời gian mili giây, nibble phiên bản 7 và biến thể RFC ở các vị trí chuẩn;
// phần còn lại là ngẫu nhiên mật mã. JDK chưa có bộ sinh UUIDv7 nên lát này tự sinh tại đây.
final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	static UUID next() {
		long most = (System.currentTimeMillis() << 16) | 0x7000L | RANDOM.nextInt(1 << 12);
		long least = 0x8000_0000_0000_0000L | (RANDOM.nextLong() & 0x3fff_ffff_ffff_ffffL);
		return new UUID(most, least);
	}

}
