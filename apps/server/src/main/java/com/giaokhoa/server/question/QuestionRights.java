package com.giaokhoa.server.question;

import org.springframework.security.oauth2.jwt.Jwt;

import com.giaokhoa.server.tenancy.TenantContext;

// Điểm nối duy nhất để mọi endpoint của feat quyết quyền, theo đúng hợp đồng đầu vào/đầu ra của dịch vụ quyết
// định quyền dùng chung: ngữ cảnh tenant, tài nguyên, phiên bản và mã hành động; kết quả là cho phép hoặc từ
// chối. Khi lõi quyền của F4 (grant, phân công) có dữ kiện, bản cài đặt đầy đủ thay vào đúng giao diện này mà
// các endpoint không đổi.
public interface QuestionRights {

	String VIEW_CONTENT = "view_content";

	String VIEW_ANSWER = "view_answer";

	String USE = "use";

	String COPY = "copy";

	String SHARE = "share";

	String SUBMIT = "submit";

	String EDIT_DRAFT = "edit_draft";

	String PUBLISH = "publish";

	// Hai hành động chuyên biệt cho nguồn LaTeX của khối công thức, quyết trên cùng điểm nối quyền này: đọc
	// nguồn và sửa nguồn độc lập với mọi hành động trên, không hành động nào suy ra hai hành động này.
	String READ_MATH_SOURCE = "read_math_source";

	String EDIT_MATH_SOURCE = "edit_math_source";

	// Quyền soạn nền tảng của người gọi: điều kiện để tạo tài nguyên cá nhân mới, không cấp quyền gì trên tài
	// nguyên của người khác.
	boolean personalAuthor(Jwt jwt);

	// Quyết định cho một hành động trên đúng một phiên bản; version có thể null với các hành động ở mức tài
	// nguyên. Mặc định từ chối: chỉ nguồn quyền được công nhận tường minh mới cho phép.
	boolean allowed(TenantContext context, Question question, QuestionVersion version, String action);

}
