package com.giaokhoa.server.organization;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ngữ cảnh khách không mang khoá phân vùng, nên cả discriminator của Hibernate lẫn chính sách Row Level Security
 * đều không tự mở được dữ liệu của trung tâm cho khách. Các đọc dành cho khách vì thế dùng SQL thuần trong giao
 * dịch đã ghim tường minh biến phân vùng về đúng trung tâm của trang tại điểm này, và luôn kèm điều kiện lọc theo
 * sub của người gọi trong câu truy vấn; chính sách RLS vẫn giữ nguyên thuần theo phân vùng như mọi bảng khác.
 */
@Component
class GuestPartitionScope {

	private final OrganizationDirectory directory;

	private final JdbcTemplate jdbc;

	GuestPartitionScope(OrganizationDirectory directory, JdbcTemplate jdbc) {
		this.directory = directory;
		this.jdbc = jdbc;
	}

	UUID pinPartitionForPage(String organizationAlias) {
		UUID organizationId = this.directory.findOrganizationIdByAlias(organizationAlias)
			.orElseThrow(OrganizationErrors::membershipUnverifiable);
		this.jdbc.queryForObject("select set_config('app.partition_key', ?, true)", String.class,
				organizationId.toString());
		return organizationId;
	}

}
