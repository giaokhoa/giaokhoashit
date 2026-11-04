package com.giaokhoa.server.tenancy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Đường ghi/đọc tối thiểu trên {@link TenantRecord} để nghiệm thu khung: ghi chỉ trong phân vùng hiệu lực của
 * request, đọc dựa vào discriminator của Hibernate và RLS để chỉ thấy đúng phân vùng đó.
 */
@Service
public class TenancyCheckService {

	private final TenantRecordRepository records;

	public TenancyCheckService(TenantRecordRepository records) {
		this.records = records;
	}

	public record RecordView(UUID id, String partitionKey, String ownerSub, String content,
			OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	@Transactional
	public RecordView createRecord(String content) {
		TenantContext context = TenantContexts.current();
		if (context == null || context.partitionKey() == null) {
			// Chế độ khách không có phân vùng để ghi: từ chối trước khi chạm dữ kiện, không để discriminator hay RLS
			// phải chặn thay ở tầng dưới.
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
		TenantRecord record = new TenantRecord();
		record.setId(UUID.randomUUID());
		record.setOwnerSub(context.sub());
		record.setContent(content);
		OffsetDateTime now = OffsetDateTime.now();
		record.setCreatedAt(now);
		record.setUpdatedAt(now);
		return view(this.records.saveAndFlush(record));
	}

	@Transactional(readOnly = true)
	public List<RecordView> listRecords() {
		return this.records.findAll().stream().map(TenancyCheckService::view).toList();
	}

	private static RecordView view(TenantRecord record) {
		return new RecordView(record.getId(), record.getPartitionKey(), record.getOwnerSub(), record.getContent(),
				record.getCreatedAt(), record.getUpdatedAt());
	}

}
