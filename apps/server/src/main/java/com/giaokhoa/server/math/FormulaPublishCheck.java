package com.giaokhoa.server.math;

import org.springframework.stereotype.Component;

import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.VersionPublishCheck;

// Nối cổng phát hành của lát LaTeX vào luồng phát hành của feat câu hỏi qua điểm nối dùng chung: một phiên
// bản còn khối công thức chưa có ảnh sẵn sàng khớp revision thì không phát hành được.
@Component
class FormulaPublishCheck implements VersionPublishCheck {

	private final MathFormulaService service;

	FormulaPublishCheck(MathFormulaService service) {
		this.service = service;
	}

	@Override
	public void checkPublishable(QuestionVersion version) {
		this.service.assertPublishable(version);
	}

}
