package com.giaokhoa.server.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageProcessingTests {

	private static final long ENOUGH_BYTES = 5L * 1024 * 1024;

	private static final long ENOUGH_PIXELS = 20_000_000L;

	// Ảnh WebP lossy 1x1 hợp lệ (fixture chuẩn 44 byte) để kiểm đường đọc phần đầu VP8 mà JDK không giải mã
	// được bằng ImageIO.
	private static final byte[] WEBP_1X1 = Base64.getDecoder()
			.decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA=");

	@Test
	@DisplayName("Ảnh PNG và JPEG hợp lệ được nhận theo nội dung thật, giữ nguyên byte gốc và checksum của đúng byte đó")
	void validPngAndJpegKeepOriginalBytes() throws IOException {
		byte[] png = image("png");
		ImageProcessing.Processed processedPng = ImageProcessing.process(png, ENOUGH_BYTES, ENOUGH_PIXELS);
		assertEquals("image/png", processedPng.contentType());
		assertEquals(7, processedPng.width());
		assertEquals(5, processedPng.height());
		assertTrue(java.util.Arrays.equals(png, processedPng.bytes()));
		assertEquals(ImageProcessing.sha256(png), processedPng.checksumSha256());

		byte[] jpeg = image("jpeg");
		ImageProcessing.Processed processedJpeg = ImageProcessing.process(jpeg, ENOUGH_BYTES, ENOUGH_PIXELS);
		assertEquals("image/jpeg", processedJpeg.contentType());
		assertEquals(7, processedJpeg.width());
	}

	@Test
	@DisplayName("Ảnh WebP hợp lệ được nhận theo chữ ký RIFF và kích thước đọc từ phần đầu VP8")
	void validWebpIsAcceptedFromHeader() {
		ImageProcessing.Processed processed = ImageProcessing.process(WEBP_1X1, ENOUGH_BYTES, ENOUGH_PIXELS);
		assertEquals("image/webp", processed.contentType());
		assertEquals(1, processed.width());
		assertEquals(1, processed.height());
	}

	@Test
	@DisplayName("Tệp giả mạo loại bằng nội dung khác hẳn ảnh, tệp ngoài danh sách cho phép và ảnh hỏng đều bị từ chối")
	void spoofedAndDisallowedContentIsRejected() {
		byte[] html = "<html><body>gia mao anh</body></html>".getBytes(StandardCharsets.UTF_8);
		assertEquals("FILE_REJECTED",
				assertThrows(AttachmentException.class,
						() -> ImageProcessing.process(html, ENOUGH_BYTES, ENOUGH_PIXELS)).code());
		byte[] zip = { 0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4 };
		assertThrows(AttachmentException.class, () -> ImageProcessing.process(zip, ENOUGH_BYTES, ENOUGH_PIXELS));
		byte[] tex = "\\frac{1}{2}".getBytes(StandardCharsets.UTF_8);
		assertThrows(AttachmentException.class, () -> ImageProcessing.process(tex, ENOUGH_BYTES, ENOUGH_PIXELS));
		byte[] truncatedPng = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0 };
		assertThrows(AttachmentException.class,
				() -> ImageProcessing.process(truncatedPng, ENOUGH_BYTES, ENOUGH_PIXELS));
	}

	@Test
	@DisplayName("Tệp vượt giới hạn byte và ảnh vượt giới hạn megapixel bị từ chối trước khi giải mã trọn")
	void oversizeAndOverPixelAreRejected() throws IOException {
		byte[] png = image("png");
		assertThrows(AttachmentException.class, () -> ImageProcessing.process(png, png.length - 1, ENOUGH_PIXELS));
		assertThrows(AttachmentException.class,
				() -> ImageProcessing.process(largePngHeader(6000, 4000), ENOUGH_BYTES, ENOUGH_PIXELS));
	}

	// PNG chỉ có phần đầu IHDR khai 6000x4000 (24 megapixel): phép kiểm số điểm ảnh đọc kích thước từ phần
	// đầu qua ImageReader nên từ chối được mà không cần giải mã một ảnh thật 24 megapixel.
	private static byte[] largePngHeader(int width, int height) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a });
		out.writeBytes(new byte[] { 0, 0, 0, 13 });
		out.writeBytes("IHDR".getBytes(StandardCharsets.US_ASCII));
		out.writeBytes(new byte[] { (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width,
				(byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height, 8, 2, 0, 0, 0 });
		out.writeBytes(new byte[] { 0, 0, 0, 0 });
		return out.toByteArray();
	}

	private static byte[] image(String format) throws IOException {
		BufferedImage image = new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0x336699);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

}
