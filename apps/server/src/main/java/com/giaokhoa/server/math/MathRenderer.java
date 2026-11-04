package com.giaokhoa.server.math;

// Cổng render duy nhất của server: phía nghiệp vụ chỉ thấy ảnh PNG cùng số đo hiển thị và thông tin bộ render
// đã sinh ảnh; cách render (worker, thư viện, tiến trình) nằm sau giao diện này để test thay bằng bản giả lập
// và để đường render đổi hiện thực mà không đổi hợp đồng.
public interface MathRenderer {

	record RenderedImage(byte[] png, int width, int height, int baselineOffset, String rendererVersion,
			String renderConfig) {
	}

	RenderedImage render(String source, boolean display);

	// Lỗi render luôn an toàn: chỉ mang một mã lỗi kỹ thuật, không bao giờ mang theo nguồn LaTeX hay chi tiết
	// cú pháp của nguồn trong thông điệp.
	class MathRenderException extends RuntimeException {

		private final String code;

		public MathRenderException(String code) {
			super("Render công thức thất bại: " + code);
			this.code = code;
		}

		public String code() {
			return this.code;
		}

	}

}
