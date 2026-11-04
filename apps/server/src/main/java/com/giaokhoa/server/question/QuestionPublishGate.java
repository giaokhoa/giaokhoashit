package com.giaokhoa.server.question;

import java.util.UUID;

// Cổng kiểm trước phát hành do các lát gắn thêm dữ kiện vào phiên bản (hiện tại là lát tệp đính kèm) cài đặt:
// phát hành chỉ chốt khi mọi dữ kiện gắn thêm đã ở trạng thái sẵn sàng. Không có bản cài đặt nào thì cổng
// không chặn gì, giữ nguyên hành vi phát hành hiện hành.
public interface QuestionPublishGate {

	void assertPublishable(UUID versionId);

}
