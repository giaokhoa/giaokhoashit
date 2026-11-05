package com.giaokhoa.server.assignment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.giaokhoa.server.access.AccessResource;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bản đọc thật của {@link ExamVersionSnapshots}: trạng thái và định danh tài nguyên của phiên bản lấy từ hàng
 * {@code exam_version} của lát đề (cộng điều kiện nghiệp vụ trên hàng lõi {@code resource} có cùng định danh),
 * còn nội dung từng mục lấy từ các hàng {@code exam_item_snapshot} đã đóng băng tại thời điểm phát hành — văn
 * bản nội dung được giữ nguyên từng ký tự trong cột text và được lắp lại thành thân câu, lựa chọn, đáp án và
 * tiêu chí chấm tại đây, cùng khuôn với bản đọc snapshot của lát chấm. Định danh phiên bản không mang theo
 * phân vùng chứa nó, nên việc định vị thử lần lượt các phân vùng ứng viên của người gọi (phân vùng hiện hành,
 * cá nhân, trung tâm của trang) trong phiên riêng của từng phân vùng — cùng cách dịch vụ quyết quyền định vị
 * tài nguyên; riêng đường đọc của lượt làm, khi các ứng viên đó không có phiên bản thì thử tiếp phân vùng cá
 * nhân của giáo viên đã tạo lần giao đang ghim phiên bản này trong phân vùng của trang, vì đề cá nhân của
 * chính giáo viên đó là nhà thường gặp của phiên bản đã giao. Không phân vùng nào có thì trả rỗng (fail-closed).
 */
@Component
public class JdbcExamVersionSnapshots implements ExamVersionSnapshots {

	private final JdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	private final AssignmentPartitions partitions;

	public JdbcExamVersionSnapshots(JdbcTemplate jdbc, ObjectMapper objectMapper, AssignmentPartitions partitions) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.partitions = partitions;
	}

	@Override
	public Optional<Snapshot> findByVersionId(UUID versionId) {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			return read(versionId, null);
		}
		for (String partition : candidatePartitions(context)) {
			Optional<Snapshot> found = this.partitions.inPartition(partition, context.sub(),
					() -> read(versionId, partition));
			if (found.isPresent()) {
				return found;
			}
		}
		return assignerPartition(context, versionId).flatMap(partition -> this.partitions
				.inPartition(partition, context.sub(), () -> read(versionId, partition)));
	}

	private List<String> candidatePartitions(TenantContext context) {
		LinkedHashSet<String> partitions = new LinkedHashSet<>();
		if (context.partitionKey() != null) {
			partitions.add(context.partitionKey());
		}
		partitions.add("user:" + context.sub());
		if (context.organizationId() != null) {
			partitions.add(context.organizationId());
		}
		if (context.platformAdmin()) {
			partitions.add("system");
		}
		return List.copyOf(partitions);
	}

	private Optional<String> assignerPartition(TenantContext context, UUID versionId) {
		if (context.partitionKey() == null) {
			return Optional.empty();
		}
		return this.partitions.inPartition(context.partitionKey(), context.sub(),
				() -> this.jdbc
						.query("select assigned_by_sub from exam_assignment where partition_key = ?"
								+ " and exam_version_id = ?", (rs, row) -> "user:" + rs.getString("assigned_by_sub"),
								context.partitionKey(), versionId)
						.stream()
						.findFirst());
	}

	private Optional<Snapshot> read(UUID versionId, String partition) {
		StringBuilder versionSql = new StringBuilder("""
				select ev.exam_id, ev.status, r.business_condition
				from exam_version ev left join resource r on r.id = ev.exam_id
				where ev.id = ?
				""");
		List<Object> versionArgs = new ArrayList<>();
		versionArgs.add(versionId);
		if (partition != null) {
			versionSql.append(" and ev.partition_key = ?");
			versionArgs.add(partition);
		}
		List<Snapshot> versions = this.jdbc.query(versionSql.toString(),
				(rs, row) -> new Snapshot(versionId, rs.getObject("exam_id", UUID.class), rs.getString("status"),
						AccessResource.CONDITION_PENDING_REVIEW.equals(rs.getString("business_condition")),
						readItems(versionId, partition)),
				versionArgs.toArray());
		return versions.stream().findFirst();
	}

	private List<Item> readItems(UUID versionId, String partition) {
		StringBuilder sql = new StringBuilder("""
				select exam_item_id, question_type, position, score, content
				from exam_item_snapshot where exam_version_id = ?
				""");
		List<Object> args = new ArrayList<>();
		args.add(versionId);
		if (partition != null) {
			sql.append(" and partition_key = ?");
			args.add(partition);
		}
		sql.append(" order by position");
		return this.jdbc.query(sql.toString(), (rs, row) -> toItem(rs.getObject("exam_item_id", UUID.class),
				rs.getString("question_type"), rs.getInt("position"), rs.getBigDecimal("score"),
				rs.getString("content")), args.toArray());
	}

	private Item toItem(UUID itemId, String type, int position, java.math.BigDecimal points, String contentJson) {
		JsonNode content;
		try {
			content = this.objectMapper.readTree(contentJson);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Nội dung snapshot của một mục đề không phải JSON hợp lệ.", ex);
		}
		List<Choice> choices = new ArrayList<>();
		JsonNode choicesNode = content.get("choices");
		if (choicesNode != null && choicesNode.isArray()) {
			choicesNode.forEach(choice -> choices.add(new Choice(text(choice, "id"), text(choice, "text"))));
		}
		return new Item(itemId, type, position, points, text(content, "stem"), List.copyOf(choices),
				text(content, "correctChoiceId"), text(content, "rubric"), attachmentsOf(content),
				formulaImagesOf(content));
	}

	private static List<Attachment> attachmentsOf(JsonNode content) {
		List<Attachment> attachments = new ArrayList<>();
		JsonNode nodes = content.get("attachments");
		if (nodes == null || !nodes.isArray()) {
			return List.of();
		}
		nodes.forEach(node -> attachments.add(new Attachment(uuid(node, "blobId"), text(node, "position"),
				text(node, "blockId"), text(node, "displayName"), text(node, "contentType"), integer(node, "width"),
				integer(node, "height"), text(node, "blobPartitionKey"))));
		return List.copyOf(attachments);
	}

	private static List<FormulaImage> formulaImagesOf(JsonNode content) {
		List<FormulaImage> images = new ArrayList<>();
		JsonNode nodes = content.get("formulaImages");
		if (nodes == null || !nodes.isArray()) {
			return List.of();
		}
		nodes.forEach(node -> images.add(new FormulaImage(text(node, "blockId"), text(node, "section"),
				integerOrZero(node, "position"), text(node, "fileRef"), uuid(node, "imageBlobId"),
				integer(node, "width"), integer(node, "height"), integer(node, "baselineOffset"),
				text(node, "description"), text(node, "blobPartitionKey"))));
		return List.copyOf(images);
	}

	private static UUID uuid(JsonNode node, String field) {
		String value = text(node, field);
		return value != null ? UUID.fromString(value) : null;
	}

	private static Integer integer(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? value.asInt() : null;
	}

	private static int integerOrZero(JsonNode node, String field) {
		Integer value = integer(node, field);
		return value != null ? value : 0;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isTextual() ? value.asText() : null;
	}

}
