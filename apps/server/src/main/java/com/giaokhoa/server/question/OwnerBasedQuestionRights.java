package com.giaokhoa.server.question;

import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.giaokhoa.server.tenancy.TenantContext;

// Bản cài đặt hiện tại của điểm nối quyết quyền: mới chỉ có hai nguồn quyền có dữ kiện — chủ sở hữu của tài
// nguyên (có mọi hành động trên tài nguyên của mình) và quyền soạn nền tảng personal-author trong token. Grant
// và phân công của lõi F4 chưa có dữ kiện nên tài khoản khác mặc định bị từ chối mọi hành động; khi F4 có dữ
// kiện, nguồn quyền đó thay vào đây, không rải phép kiểm quyền ra các endpoint.
@Component
class OwnerBasedQuestionRights implements QuestionRights {

	private static final String PERSONAL_AUTHOR_ROLE = "personal-author";

	@Override
	public boolean personalAuthor(Jwt jwt) {
		if (jwt == null) {
			return false;
		}
		Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
		if (realmAccess == null) {
			return false;
		}
		Object roles = realmAccess.get("roles");
		return roles instanceof List<?> list && list.contains(PERSONAL_AUTHOR_ROLE);
	}

	@Override
	public boolean allowed(TenantContext context, Question question, QuestionVersion version, String action) {
		return context != null && question != null && context.sub().equals(question.getOwnerSub());
	}

}
