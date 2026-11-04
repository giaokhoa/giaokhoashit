package com.giaokhoa.server.attachment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

// Kiểm tra và chuẩn hoá một ảnh đính kèm: loại tệp quyết từ chữ ký byte thật và khả năng giải mã, không tin
// đuôi tệp hay Content-Type do client khai. PNG/JPEG giải mã trọn bằng ImageIO; byte gốc của tệp hợp lệ được
// giữ nguyên để bản người tải lên xác nhận ở bước xem trước là đúng bản được chốt sau đó, không nén lại.
// shortcut: JDK không có bộ giải mã WebP, nên WebP mới kiểm ở mức khung RIFF/VP8/VP8L/VP8X để lấy kích thước
// và chặn tệp giả danh; khi kho thật cần giải mã WebP thì bổ sung bộ giải mã vào bước này.
final class ImageProcessing {

	record Processed(byte[] bytes, String contentType, int width, int height, String checksumSha256) {
	}

	private ImageProcessing() {
	}

	static Processed process(byte[] raw, long maxBytes, long maxPixels) {
		if (raw == null || raw.length == 0) {
			throw AttachmentException.fileRejected("Tệp rỗng.");
		}
		if (raw.length > maxBytes) {
			throw AttachmentException.fileRejected("Tệp vượt quá giới hạn dung lượng cho phép.");
		}
		if (isPng(raw)) {
			return decode(raw, "png", "image/png", maxPixels);
		}
		if (isJpeg(raw)) {
			return decode(raw, "jpeg", "image/jpeg", maxPixels);
		}
		if (isWebp(raw)) {
			int[] size = webpSize(raw);
			checkPixels(size[0], size[1], maxPixels);
			return new Processed(raw, "image/webp", size[0], size[1], sha256(raw));
		}
		throw AttachmentException.fileRejected(
				"Loại tệp không được phép: chỉ nhận ảnh PNG, JPEG hoặc WebP theo nội dung thật của tệp.");
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Thiếu thuật toán SHA-256.", ex);
		}
	}

	private static Processed decode(byte[] raw, String format, String contentType, long maxPixels) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
			if (input == null) {
				throw AttachmentException.fileRejected("Không đọc được dữ kiện ảnh.");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
			if (!readers.hasNext()) {
				throw AttachmentException.fileRejected("Không có bộ giải mã cho định dạng ảnh này.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				checkPixels(width, height, maxPixels);
				if (reader.read(0) == null) {
					throw AttachmentException.fileRejected("Ảnh không giải mã được.");
				}
				return new Processed(raw, contentType, width, height, sha256(raw));
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException ex) {
			throw AttachmentException.fileRejected("Ảnh không giải mã được.");
		}
	}

	private static void checkPixels(int width, int height, long maxPixels) {
		if (width <= 0 || height <= 0 || (long) width * height > maxPixels) {
			throw AttachmentException.fileRejected("Ảnh vượt quá giới hạn số điểm ảnh cho phép.");
		}
	}

	private static boolean isPng(byte[] raw) {
		byte[] signature = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
		if (raw.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (raw[i] != signature[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean isJpeg(byte[] raw) {
		return raw.length >= 3 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xd8 && (raw[2] & 0xff) == 0xff;
	}

	private static boolean isWebp(byte[] raw) {
		return raw.length >= 20 && ascii(raw, 0, 4).equals("RIFF") && ascii(raw, 8, 4).equals("WEBP");
	}

	// Kích thước khung của WebP nằm ngay trong phần đầu của chunk ảnh, theo đặc tả RIFF WebP của Google:
	// VP8X giữ (rộng - 1) và (cao - 1) ở 24 bit tại offset 24/27; VP8 lossy giữ hai số 14 bit sau mã bắt đầu
	// 9d 01 2a; VP8L gói (rộng - 1) và (cao - 1) mỗi số 14 bit ngay sau byte chữ ký 0x2f.
	private static int[] webpSize(byte[] raw) {
		String chunk = ascii(raw, 12, 4);
		try {
			switch (chunk) {
				case "VP8X" -> {
					return new int[] { 1 + le24(raw, 24), 1 + le24(raw, 27) };
				}
				case "VP8L" -> {
					if ((raw[20] & 0xff) != 0x2f) {
						throw new IllegalArgumentException("VP8L sai chữ ký.");
					}
					int width = 1 + ((raw[21] & 0xff) | ((raw[22] & 0x3f) << 8));
					int height = 1 + (((raw[22] & 0xff) >> 6) | ((raw[23] & 0xff) << 2) | ((raw[24] & 0x0f) << 10));
					return new int[] { width, height };
				}
				case "VP8 " -> {
					if ((raw[23] & 0xff) != 0x9d || (raw[24] & 0xff) != 0x01 || (raw[25] & 0xff) != 0x2a) {
						throw new IllegalArgumentException("VP8 sai mã bắt đầu.");
					}
					int width = (raw[26] & 0xff | ((raw[27] & 0xff) << 8)) & 0x3fff;
					int height = (raw[28] & 0xff | ((raw[29] & 0xff) << 8)) & 0x3fff;
					return new int[] { width, height };
				}
				default -> throw new IllegalArgumentException("Chunk WebP không được hỗ trợ.");
			}
		}
		catch (RuntimeException ex) {
			throw AttachmentException.fileRejected("Ảnh WebP không đọc được phần đầu khung hình.");
		}
	}

	private static int le24(byte[] raw, int offset) {
		return (raw[offset] & 0xff) | ((raw[offset + 1] & 0xff) << 8) | ((raw[offset + 2] & 0xff) << 16);
	}

	private static String ascii(byte[] raw, int offset, int length) {
		return new String(raw, offset, length, StandardCharsets.US_ASCII);
	}

}
