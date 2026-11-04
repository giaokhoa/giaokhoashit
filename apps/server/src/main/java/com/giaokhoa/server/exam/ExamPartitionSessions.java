package com.giaokhoa.server.exam;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Chạy một đoạn việc trong đúng một phân vùng dữ kiện: một đề nằm ở đúng một phân vùng (của chủ sở hữu) trong
 * khi người gọi có thể đang ở ngữ cảnh của một trang khác, nên thao tác trên đề không dùng thẳng session của
 * request. Cơ chế theo cùng khuôn phiên theo phân vùng của lõi quyền: đổi {@link TenantContext} sang ngữ cảnh
 * tổng hợp chỉ mang danh tính người gọi và khoá phân vùng đích, rồi mở giao dịch {@code REQUIRES_NEW} — giao
 * dịch mới làm Hibernate mở session với đúng tenant id đó và transaction manager đặt biến RLS từ chính ngữ cảnh
 * đã đổi tại thời điểm giao dịch bắt đầu. Không tồn tại phiên "root" không thuộc phân vùng nào.
 */
@Component
public class ExamPartitionSessions {

	private final TransactionTemplate writeTemplate;

	private final TransactionTemplate readTemplate;

	public ExamPartitionSessions(PlatformTransactionManager transactionManager) {
		this.writeTemplate = new TransactionTemplate(transactionManager);
		this.writeTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTemplate = new TransactionTemplate(transactionManager);
		this.readTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTemplate.setReadOnly(true);
	}

	public <T> T inPartition(String actorSub, String partitionKey, Supplier<T> work) {
		return run(this.writeTemplate, actorSub, partitionKey, work);
	}

	public <T> T readInPartition(String actorSub, String partitionKey, Supplier<T> work) {
		return run(this.readTemplate, actorSub, partitionKey, work);
	}

	private <T> T run(TransactionTemplate template, String actorSub, String partitionKey, Supplier<T> work) {
		TenantContext previous = TenantContexts.current();
		TenantContexts.set(new TenantContext(actorSub, null, null, List.of(), false, false, partitionKey));
		try {
			return template.execute(status -> work.get());
		}
		finally {
			if (previous == null) {
				TenantContexts.clear();
			}
			else {
				TenantContexts.set(previous);
			}
		}
	}

}
