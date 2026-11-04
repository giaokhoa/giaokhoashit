package com.giaokhoa.server.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.ObjectMapper;

// Kiểm đường render thật qua worker Node (MathJax sang SVG rồi resvg sang PNG): lớp này chỉ chạy khi biến
// LMS_MATH_WORKER_SCRIPT trỏ tới script worker đã cài dependencies bằng npm ci, để bộ test mặc định không
// phụ thuộc Node; kết quả bỏ qua không được tính là đạt.
@EnabledIfEnvironmentVariable(named = "LMS_MATH_WORKER_SCRIPT", matches = ".+")
class NodeMathRenderWorkerTests {

	private static NodeMathRenderWorker worker;

	@BeforeAll
	static void setUp() {
		MathRenderProperties properties = new MathRenderProperties();
		properties.setWorkerScript(System.getenv("LMS_MATH_WORKER_SCRIPT"));
		worker = new NodeMathRenderWorker(properties, new ObjectMapper());
	}

	@Test
	@DisplayName("NT-35 trên bộ mẫu M5c: mười loại công thức được hỗ trợ đều render thành PNG có số đo hiển thị hợp lệ ở hệ số phóng 2, gồm cả công thức hoá học qua mhchem")
	void sampleSetRenders() {
		List<String> samples = List.of("\\frac{\\frac{a}{b}}{c}", "\\sqrt{\\sqrt{x}+1}",
				"\\sum_{i=1}^{n} i + \\int_0^1 x^2 \\, dx", "\\lim_{x \\to 0} \\frac{\\sin x}{x}",
				"\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
				"f(x)=\\begin{cases} x^2 & x \\ge 0 \\\\ -x & x < 0 \\end{cases}", "x_1^{2} + y_{ij}^{\\alpha}",
				"\\alpha + \\beta \\le \\gamma \\neq \\delta",
				"\\begin{aligned} a &= b + c \\\\ d &= e \\end{aligned}", "\\ce{SO4^2- + Ba^2+ -> BaSO4 v}");
		for (String sample : samples) {
			MathRenderer.RenderedImage image = worker.render(sample, sample.contains("\\begin"));
			assertTrue(image.png().length > 200, "Ảnh không được rỗng cho mẫu: " + sample);
			assertTrue(isPng(image.png()), "Đầu ra phải là PNG cho mẫu: " + sample);
			assertTrue(image.width() > 0 && image.height() > 0, "Số đo hiển thị phải dương: " + sample);
			assertTrue(image.baselineOffset() >= 0 && image.baselineOffset() <= image.height(),
					"Độ lệch đường cơ sở phải nằm trong chiều cao ảnh: " + sample);
			assertTrue(image.rendererVersion().contains("mathjax-full@3.2.2"),
					"Bản ghi phải ghim phiên bản bộ render: " + sample);
		}
	}

	@Test
	@DisplayName("NT-10 ở mức byte: PNG của một công thức chứa dấu nhận diện riêng trong nguồn không chứa dấu đó và không mang bất kỳ chunk metadata nào")
	void markerNeverReachesPng() {
		MathRenderer.RenderedImage image = worker.render("\\frac{DAUNHANDIEN987}{2}", false);
		String asText = new String(image.png(), java.nio.charset.StandardCharsets.ISO_8859_1);
		assertTrue(!asText.contains("DAUNHANDIEN987"), "PNG không được chứa dấu nhận diện của nguồn");
		int offset = 8;
		while (offset < image.png().length) {
			int length = ((image.png()[offset] & 0xFF) << 24) | ((image.png()[offset + 1] & 0xFF) << 16)
					| ((image.png()[offset + 2] & 0xFF) << 8) | (image.png()[offset + 3] & 0xFF);
			String type = new String(image.png(), offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
			assertTrue(List.of("IHDR", "IDAT", "IEND").contains(type),
					"PNG chỉ gồm chunk dữ kiện ảnh, không có chunk metadata: " + type);
			offset += 12 + length;
		}
	}

	@Test
	@DisplayName("NT-11: nguồn sai cú pháp và macro đệ quy vô hạn đều hỏng an toàn thành lỗi render, thông điệp lỗi không chứa nguồn")
	void invalidAndRecursiveSourcesFailSafely() {
		MathRenderer.MathRenderException syntax = assertThrows(MathRenderer.MathRenderException.class,
				() -> worker.render("\\frac{1}{", false));
		assertTrue(!syntax.getMessage().contains("\\frac"), "Thông điệp lỗi không được chứa nguồn");
		assertThrows(MathRenderer.MathRenderException.class,
				() -> worker.render("\\def\\x{\\x aaaaaaaaaa} \\x", false));
	}

	@Test
	@DisplayName("NT-14 ở mức chức năng: đầu vào chứa lệnh HTML, lệnh đọc tệp hay macro lạ đều bị từ chối, và trạng thái macro không truyền sang tác vụ sau")
	void dangerousInputsRejectedAndMacrosIsolated() {
		assertThrows(MathRenderer.MathRenderException.class,
				() -> worker.render("\\href{https://example.invalid}{x}", false));
		assertThrows(MathRenderer.MathRenderException.class,
				() -> worker.render("\\includegraphics{/etc/passwd}", false));
		assertThrows(MathRenderer.MathRenderException.class, () -> worker.render("\\macroLa{x}", false));
		worker.render("\\def\\foo{42}\\foo", false);
		assertThrows(MathRenderer.MathRenderException.class, () -> worker.render("\\foo", false));
	}

	@Test
	@DisplayName("Giới hạn độ dài nguồn áp ngay tại worker: nguồn dài hơn cấu hình bị từ chối bằng mã SOURCE_TOO_LONG")
	void sourceLengthLimitEnforced() {
		MathRenderer.MathRenderException ex = assertThrows(MathRenderer.MathRenderException.class,
				() -> worker.render("x".repeat(2001), false));
		assertEquals("SOURCE_TOO_LONG", ex.code());
	}

	private boolean isPng(byte[] bytes) {
		return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E
				&& bytes[3] == 0x47;
	}

}
