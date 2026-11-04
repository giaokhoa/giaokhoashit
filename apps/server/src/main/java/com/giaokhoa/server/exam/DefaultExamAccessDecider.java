package com.giaokhoa.server.exam;

import org.springframework.stereotype.Component;

import com.giaokhoa.server.access.AccessAction;
import com.giaokhoa.server.access.AccessDecisionService;
import com.giaokhoa.server.tenancy.TenantContext;

/**
 * Cổng quyết quyền của lát đề: mọi quyết định đều uỷ thác sang {@link AccessDecisionService} của lõi quyền, trên
 * các hàng lõi tương ứng của chính đề và câu được tiêu thụ. Các hành động của lát đề dùng đúng mã của tập hành
 * động lõi nên ánh xạ là một-một; kết quả trả về nguyên vẹn mã lý do và nguồn quyết định của lõi, trong đó tập
 * hành động của nguồn {@code TENANT_ADMIN} ở lõi không chứa hành động xem đáp án — đúng tiêu chí của đặc tả đề
 * mà không cần một luật riêng nào ở lát này.
 */
@Component
public class DefaultExamAccessDecider implements ExamAccessDecider {

	private final AccessDecisionService decisions;

	public DefaultExamAccessDecider(AccessDecisionService decisions) {
		this.decisions = decisions;
	}

	@Override
	public Decision decide(TenantContext context, Exam exam, ExamVersion version, ExamAction action) {
		AccessDecisionService.Decision result = this.decisions.decide(context, exam.getId(),
				version == null ? null : version.getId(), toCoreAction(action));
		return new Decision(result.allowed(), result.reasonCode(), result.source());
	}

	@Override
	public Decision decideOnQuestion(TenantContext context, QuestionVersionSource.QuestionVersionInfo question,
			ExamAction action) {
		AccessDecisionService.Decision result = this.decisions.decide(context, question.questionId(),
				question.questionVersionId(), toCoreAction(action));
		return new Decision(result.allowed(), result.reasonCode(), result.source());
	}

	private static AccessAction toCoreAction(ExamAction action) {
		return AccessAction.fromCode(action.code()).orElseThrow(() -> new IllegalArgumentException(
				"Hành động của lát đề không nằm trong tập hành động của lõi quyền: " + action.code()));
	}

}
