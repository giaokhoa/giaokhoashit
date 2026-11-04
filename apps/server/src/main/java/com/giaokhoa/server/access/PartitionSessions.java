package com.giaokhoa.server.access;

import java.util.List;
import java.util.function.Supplier;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chạy một đơn vị dữ kiện trong đúng một phân vùng: mỗi nguồn dữ kiện của lõi quyền nằm ở một phân vùng (tài
 * nguyên và phiên bản ở phân vùng của chủ sở hữu, phân công ở phân vùng của Organization, grant ở phân vùng của
 * phía nhận), nên dịch vụ quyết định và các thao tác ghi đọc/ghi từng nguồn trong phiên riêng của phân vùng đó.
 * Một phiên không đổi khoá tenant giữa chừng và không tồn tại phiên "root" hay đường tắt bộ lọc tenant để đọc
 * chéo.
 *
 * <p>
 * Ngữ cảnh dữ kiện đặt trong lúc chạy chỉ mang {@code sub} của người gọi và khoá phân vùng đích — đủ để
 * discriminator của Hibernate và điểm đặt biến RLS của khung tenancy khoá đúng phân vùng. Danh tính và vai dùng
 * cho quyết định luôn lấy từ {@link TenantContext} thật của người gọi được truyền tường minh vào dịch vụ quyết
 * định, không bao giờ đọc lại từ ngữ cảnh dữ kiện này.
 */
@Component
public class PartitionSessions {

	private final TransactionTemplate transactions;

	public PartitionSessions(PlatformTransactionManager transactionManager) {
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

	public void inPartition(String partitionKey, String actorSub, Runnable work) {
		inPartition(partitionKey, actorSub, () -> {
			work.run();
			return null;
		});
	}

}
