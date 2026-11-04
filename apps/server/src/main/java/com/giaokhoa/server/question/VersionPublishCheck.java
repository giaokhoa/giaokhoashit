package com.giaokhoa.server.question;

// Điểm nối để các lát nội dung chuyên biệt (công thức, tệp) chặn việc phát hành một phiên bản khi phần nội
// dung của chúng chưa sẵn sàng, mà không phải sửa luồng phát hành của feat câu hỏi mỗi lần thêm một lát.
// Mặc định không có bản cài đặt nào thì phát hành chạy như cũ.
public interface VersionPublishCheck {

	void checkPublishable(QuestionVersion version);

}
