package com.giaokhoa.server.files;

import java.time.Instant;
import java.util.UUID;

/**
 * Cổng lưu trữ byte của nền tệp: mọi byte đều đi qua cổng này để hiện thực kho (hiện tại là filesystem trên
 * volume riêng của service server) có thể thay bằng kho đối tượng về sau mà không đổi hợp đồng. Khoá lưu trữ
 * do server sinh; client không chọn và không ghi đè được khoá của một blob đã chốt.
 */
public interface BlobStorage {

	/** Nhận byte vào vùng tạm dưới khoá của riêng phiên tải lên; trả khoá tạm đã dùng. */
	String stage(UUID sessionId, byte[] bytes);

	/** Đọc lại byte đang ở vùng tạm theo khoá tạm. */
	byte[] readStaged(String stagingKey);

	/** Xoá byte ở vùng tạm theo khoá tạm; không làm gì khi khoá không còn tồn tại. */
	void deleteStaged(String stagingKey);

	/**
	 * Chốt byte đã kiểm chứng từ vùng tạm sang khoá lưu trữ cuối cùng. Ghi đè một khoá đã tồn tại là lỗi: blob
	 * đã chốt là bất biến.
	 */
	void promote(String stagingKey, String storageKey);

	/** Đọc byte của một blob đã chốt theo khoá lưu trữ. */
	byte[] read(String storageKey);

	/** Dọn các tệp tạm có thời điểm sửa cuối trước mốc cho trước; trả số tệp đã xoá. */
	int sweepStaging(Instant olderThan);

}
