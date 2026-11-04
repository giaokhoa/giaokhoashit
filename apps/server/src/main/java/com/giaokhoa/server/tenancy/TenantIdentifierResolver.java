package com.giaokhoa.server.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Nối discriminator của Hibernate với {@link TenantContext} của request: khoá phân vùng hiệu lực là tenant id của
 * mọi session mở trong request đó. Không có khoá (chế độ khách, hoặc ngoài ngữ cảnh request như job nền) thì trả
 * một khoá không thuộc bất kỳ phân vùng nào để truy vấn bảng phân vùng không thấy hàng nào — fail-closed thay vì
 * tắt bộ lọc.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

	// Không trùng dạng của bất kỳ khoá phân vùng thật nào (Organization ID, user:<sub>, system).
	private static final String NO_PARTITION = "";

	@Override
	public String resolveCurrentTenantIdentifier() {
		TenantContext context = TenantContexts.current();
		return context != null && context.partitionKey() != null ? context.partitionKey() : NO_PARTITION;
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		return true;
	}

}
