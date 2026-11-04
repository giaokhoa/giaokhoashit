package com.giaokhoa.server.files;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import com.giaokhoa.server.access.AccessException;

import org.springframework.stereotype.Component;

/**
 * Kiểm chứng nội dung thật của một tệp ảnh tải lên: loại tệp quyết bởi chữ ký byte và khả năng giải mã, không
 * bởi đuôi tệp hay Content-Type client khai. Danh sách cho phép ban đầu chỉ gồm PNG, JPEG và WebP; giới hạn
 * dung lượng và số điểm ảnh lấy từ cấu hình triển khai.
 */
@Component
public class ImageVerifier {

	public record VerifiedImage(String contentType, int width, int height) {
	}

	public VerifiedImage verify(byte[] bytes, long maxBytes, long maxMegapixels) {
		if (bytes == null || bytes.length == 0) {
			throw AccessException.validation("Tệp tải lên rỗng");
		}
		if (bytes.length > maxBytes) {
			throw AccessException.validation("Tệp vượt quá dung lượng tối đa cho phép");
		}
		VerifiedImage image;
		if (isPng(bytes)) {
			image = decode(bytes, "image/png");
		}
		else if (isJpeg(bytes)) {
			image = decode(bytes, "image/jpeg");
		}
		else if (isWebP(bytes)) {
			image = webpDimensions(bytes);
		}
		else {
			throw AccessException.validation("Loại tệp không thuộc danh sách cho phép: chỉ nhận ảnh PNG, JPEG, WebP");
		}
		if ((long) image.width() * image.height() > maxMegapixels) {
			throw AccessException.validation("Ảnh vượt quá số điểm ảnh tối đa cho phép");
		}
		return image;
	}

	private static boolean isPng(byte[] bytes) {
		byte[] signature = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
		if (bytes.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (bytes[i] != signature[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean isJpeg(byte[] bytes) {
		return bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
	}

	private static boolean isWebP(byte[] bytes) {
		return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
	}

	private static VerifiedImage decode(byte[] bytes, String contentType) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null) {
				throw AccessException.validation("Tệp không giải mã được thành ảnh hợp lệ");
			}
			return new VerifiedImage(contentType, image.getWidth(), image.getHeight());
		}
		catch (IOException ex) {
			throw AccessException.validation("Tệp không giải mã được thành ảnh hợp lệ");
		}
	}

	// JDK không có bộ giải mã WebP: kích thước được đọc từ chính đầu khối VP8/VP8L/VP8X của định dạng (mỗi dạng
	// lưu kích thước trừ một), đủ để áp giới hạn điểm ảnh; PNG và JPEG đi qua bộ giải mã thật ở trên.
	private static VerifiedImage webpDimensions(byte[] bytes) {
		if (bytes.length < 30) {
			throw AccessException.validation("Tệp WebP không hợp lệ");
		}
		String chunk = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
		int width;
		int height;
		switch (chunk) {
			case "VP8X" -> {
				width = (bytes[24] & 0xFF) | ((bytes[25] & 0xFF) << 8) | ((bytes[26] & 0xFF) << 16);
				height = (bytes[27] & 0xFF) | ((bytes[28] & 0xFF) << 8) | ((bytes[29] & 0xFF) << 16);
			}
			case "VP8L" -> {
				if (bytes[20] != 0x2F) {
					throw AccessException.validation("Tệp WebP không hợp lệ");
				}
				width = (bytes[21] & 0xFF) | ((bytes[22] & 0x3F) << 8);
				height = ((bytes[22] & 0xFF) >> 6) | ((bytes[23] & 0xFF) << 2) | ((bytes[24] & 0x0F) << 10);
			}
			case "VP8 " -> {
				if (bytes[23] != (byte) 0x9D || bytes[24] != 0x01 || bytes[25] != 0x2A) {
					throw AccessException.validation("Tệp WebP không hợp lệ");
				}
				width = (bytes[26] & 0xFF) | ((bytes[27] & 0x3F) << 8);
				height = (bytes[28] & 0xFF) | ((bytes[29] & 0x3F) << 8);
			}
			default -> throw AccessException.validation("Tệp WebP không hợp lệ");
		}
		return new VerifiedImage("image/webp", width + 1, height + 1);
	}

}
