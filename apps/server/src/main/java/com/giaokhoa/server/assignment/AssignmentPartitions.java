package com.giaokhoa.server.assignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chạy một đơn vị dữ kiện của khối giao/làm bài trong đúng phân vùng của Organization của trang, kể cả khi
 * người gọi là khách: ngữ cảnh khách không mang khoá phân vùng, nên discriminator của Hibernate không đóng
 * dấu được hàng và điểm đặt biến RLS của khung tenancy không mở được dữ kiện của trung tâm cho khách. Vì vậy
 * mỗi thao tác ở đây ghim tường minh một ngữ cảnh dữ kiện chỉ mang `sub` của người gọi và khoá phân vùng của
 * trang trong một giao dịch riêng, theo cùng cơ chế mà lõi quyền dùng để chạy dữ kiện theo phân vùng. Danh
 * tính và vai dùng cho quyết định quyền luôn lấy từ {@link TenantContext} thật của request được truyền tường
 * minh vào tầng dịch vụ, không bao giờ đọc lại từ ngữ cảnh dữ kiện này.
 */
@Component
public class AssignmentPartitions {

	private final TransactionTemplate transactions;

	private final OrganizationIds organizationIds;

	public AssignmentPartitions(PlatformTransactionManager transactionManager, OrganizationIds organizationIds) {
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.organizationIds = organizationIds;
	}

	/**
	 * Phân vùng dữ kiện của trang hiện tại: thành viên dùng thẳng Organization ID trong ngữ cảnh; khách không
	 * mang ID đó nên phải tra alias của trang qua đường đọc Keycloak. Rỗng khi request không mang ngữ cảnh
	 * trung tâm nào, hoặc khi alias không tra được.
	 */
	public Optional<String> pagePartition(TenantContext context) {
		if (context == null || context.organizationAlias() == null) {
			return Optional.empty();
		}
		if (context.organizationId() != null) {
			return Optional.of(context.organizationId());
		}
		return this.organizationIds.findOrganizationIdByAlias(context.organizationAlias()).map(UUID::toString);
	}

	/**
	 * Như {@link #pagePartition} nhưng phân biệt hai cách rỗng: không có ngữ cảnh trung tâm của trang là lỗi
	 * quyền (403); có alias mà không tra được định danh là không xác minh được (503, fail-closed).
	 */
	public String requirePagePartition(TenantContext context) {
		if (context == null || context.organizationAlias() == null) {
			throw AssignmentException.forbidden("Thao tác chỉ áp dụng tại trang của một trung tâm.");
		}
		return pagePartition(context).orElseThrow(AssignmentException::unverifiable);
	}

	public <T> T inPartition(String partitionKey, String actorSub, Supplier<T> work) {
		TenantContext previous = TenantContexts.current();
		TenantContexts.set(new TenantContext(actorSub, null, null, List.of(), false, false, partitionKey));
		try {
			return this.transactions.execute(status -> work.get());
		}
		finally {
			if (previous != null) {
				TenantContexts.set(previous);
			}
			else {
				TenantContexts.clear();
			}
		}
	}

}
