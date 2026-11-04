package com.giaokhoa.server.exam;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.giaokhoa.server.access.AccessResource;
import com.giaokhoa.server.access.AccessResourceRepository;
import com.giaokhoa.server.access.PartitionSessions;
import com.giaokhoa.server.access.ResourceDependency;
import com.giaokhoa.server.access.ResourceDependencyRepository;
import com.giaokhoa.server.access.ResourceVersion;
import com.giaokhoa.server.access.ResourceVersionRepository;
import com.giaokhoa.server.exam.ExamAccessDecider.Decision;
import com.giaokhoa.server.exam.QuestionVersionSource.QuestionVersionInfo;
import com.giaokhoa.server.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Nghiệp vụ soạn đề thủ công và đóng băng snapshot khi phát hành. Service không bọc giao dịch ở tầng này: một đề
 * nằm ở đúng một phân vùng trong khi người gọi có thể đang ở ngữ cảnh của một trang khác, nên mọi thao tác đọc
 * và ghi đều chạy trong phiên của đúng phân vùng của đề qua {@link PartitionSessions} — một giao dịch bao
 * ngoài sẽ mở session theo phân vùng của request và xung đột với việc đổi tenant giữa chừng. Đề dùng chung định
 * danh với tài nguyên của lõi quyền (id của đề là id của hàng {@code resource}, id của phiên bản đề là id của
 * hàng {@code resource_version}), nên tạo đề và tạo phiên bản đều ghi kèm hàng lõi tương ứng trong cùng phiên
 * phân vùng, và phát hành ghi thêm hàng phụ thuộc từ phiên bản đề tới từng phiên bản câu nó dùng.
 */
@Service
public class ExamService {

	public record ItemInput(UUID questionVersionId, BigDecimal score) {
	}

	public record ItemView(UUID id, UUID questionVersionId, String questionType, int position, BigDecimal score,
			JsonNode content) {
	}

	public record ExamView(UUID id, String resourceType, String workspaceKind, String ownerType, String ownerUserSub,
			String ownerOrganizationId, UUID collectionId, String custodyKind, String title, UUID versionId,
			int versionNumber, String status, OffsetDateTime lockedAt, String contentHash, long lockVersion,
			List<ItemView> items) {
	}

	private record LoadedData(Exam exam, ExamVersion version, List<ExamItem> itemRows,
			List<ExamItemSnapshot> snapshotRows) {
	}

	private static final String TENANT_ADMIN_ROLE = "tenant-admin";

	private final ExamRepository exams;

	private final ExamVersionRepository versions;

	private final ExamItemRepository items;

	private final ExamItemSnapshotRepository snapshots;

	private final ExamAccessDecider decider;

	private final QuestionVersionSource questionVersions;

	private final PartitionSessions sessions;

	private final AccessResourceRepository resources;

	private final ResourceVersionRepository coreVersions;

	private final ResourceDependencyRepository dependencies;

	private final ObjectMapper objectMapper;

	public ExamService(ExamRepository exams, ExamVersionRepository versions, ExamItemRepository items,
			ExamItemSnapshotRepository snapshots, ExamAccessDecider decider, QuestionVersionSource questionVersions,
			PartitionSessions sessions, AccessResourceRepository resources,
			ResourceVersionRepository coreVersions, ResourceDependencyRepository dependencies,
			ObjectMapper objectMapper) {
		this.exams = exams;
		this.versions = versions;
		this.items = items;
		this.snapshots = snapshots;
		this.decider = decider;
		this.questionVersions = questionVersions;
		this.sessions = sessions;
		this.resources = resources;
		this.coreVersions = coreVersions;
		this.dependencies = dependencies;
		this.objectMapper = objectMapper;
	}

	public ExamView createExam(TenantContext context, boolean personalAuthor, String title, String workspace,
			UUID collectionId) {
		if (title == null || title.isBlank()) {
			throw validation("Tiêu đề đề không được trống.");
		}
		String kind = workspace == null ? "personal" : workspace;
		Exam exam = new Exam();
		exam.setId(UUID.randomUUID());
		exam.setTitle(title);
		exam.setCollectionId(collectionId);
		exam.setCustodyKind("owned");
		exam.setCreatedBySub(context.sub());
		String partition = switch (kind) {
			case "personal" -> {
				if (!personalAuthor) {
					throw forbidden(
							"Tài khoản không có quyền soạn nền tảng personal-author nên không tạo được đề cá nhân.");
				}
				exam.setWorkspaceKind("personal");
				exam.setOwnerType("user");
				exam.setOwnerUserSub(context.sub());
				yield "user:" + context.sub();
			}
			case "center" -> {
				// Tạo đề thuộc trung tâm là thao tác minh thị: chỉ tenant-admin của đúng trung tâm của trang mới
				// tạo được, và đề mang chủ sở hữu là Organization đó chứ không phải người tạo.
				if (context.organizationId() == null || !context.organizationRoles().contains(TENANT_ADMIN_ROLE)) {
					throw forbidden("Chỉ tenant-admin của trung tâm mới tạo được đề thuộc trung tâm đó.");
				}
				exam.setWorkspaceKind("center");
				exam.setOwnerType("organization");
				exam.setOwnerOrganizationId(context.organizationId());
				yield context.organizationId();
			}
			default -> throw validation("Workspace không hợp lệ: " + kind + ".");
		};
		OffsetDateTime now = OffsetDateTime.now();
		exam.setCreatedAt(now);
		exam.setUpdatedAt(now);
		ExamVersion first = new ExamVersion();
		first.setId(UUID.randomUUID());
		first.setExamId(exam.getId());
		first.setVersionNumber(1);
		first.setStatus("draft");
		first.setCreatedBySub(context.sub());
		first.setCreatedAt(now);
		first.setUpdatedAt(now);
		this.sessions.inPartition(partition, context.sub(), () -> {
			this.exams.save(exam);
			this.versions.save(first);
			saveResourceMirror(exam);
			saveVersionMirror(first);
			return null;
		});
		return readView(context, partition, exam.getId(), first.getId());
	}

	public ExamView getExam(TenantContext context, UUID examId, UUID versionId) {
		return readView(context, locatePartition(context, examId), examId, versionId);
	}

	public ExamView updateDraft(TenantContext context, UUID examId, UUID versionId, String title, Long lockVersion,
			List<ItemInput> itemInputs) {
		String partition = locatePartition(context, examId);
		this.sessions.inPartition(partition, context.sub(), () -> {
			Exam exam = this.exams.findById(examId).orElseThrow(ExamService::notFound);
			ExamVersion version = versionOf(examId, versionId);
			Decision decision = this.decider.decide(context, exam, version, ExamAction.EDIT_DRAFT);
			if (!decision.allowed()) {
				if ("VERSION_LOCKED".equals(decision.reasonCode())) {
					throw lockedVersion();
				}
				throw forbidden("Người gọi không có quyền sửa bản nháp của đề này.");
			}
			return null;
		});
		checkItemsUsable(context, itemInputs);
		OffsetDateTime now = OffsetDateTime.now();
		this.sessions.inPartition(partition, context.sub(), () -> {
			Exam exam = this.exams.findById(examId).orElseThrow(ExamService::notFound);
			ExamVersion version = versionOf(examId, versionId);
			if (!"draft".equals(version.getStatus())) {
				throw lockedVersion();
			}
			if (lockVersion == null) {
				throw validation("Thiếu lockVersion của bản nháp trong yêu cầu sửa.");
			}
			if (version.getLockVersion() != lockVersion.longValue()) {
				throw new ExamException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
						"Bản nháp đã thay đổi kể từ lần đọc gần nhất (lockVersion không còn khớp); đọc lại trước khi sửa.");
			}
			if (title != null) {
				if (title.isBlank()) {
					throw validation("Tiêu đề đề không được trống.");
				}
				exam.setTitle(title);
				exam.setUpdatedAt(now);
			}
			this.items.deleteByExamVersionId(version.getId());
			this.items.flush();
			int position = 1;
			for (ItemInput input : itemInputs) {
				ExamItem row = new ExamItem();
				row.setId(UUID.randomUUID());
				row.setExamVersionId(version.getId());
				row.setExamId(examId);
				row.setQuestionVersionId(input.questionVersionId());
				row.setPosition(position++);
				row.setScore(input.score());
				row.setCreatedAt(now);
				row.setUpdatedAt(now);
				this.items.save(row);
			}
			// updated_at của phiên bản luôn đổi ở mỗi lượt sửa để cột khoá lạc quan tăng kể cả khi lượt sửa chỉ
			// thay danh sách mục: hai lượt sửa mang cùng lockVersion thì lượt lưu sau phải bị chặn.
			version.setUpdatedAt(now);
			return null;
		});
		return readView(context, partition, examId, versionId);
	}

	public ExamView createVersion(TenantContext context, UUID examId, UUID sourceVersionId) {
		String partition = locatePartition(context, examId);
		OffsetDateTime now = OffsetDateTime.now();
		UUID newVersionId = this.sessions.inPartition(partition, context.sub(), () -> {
			Exam exam = this.exams.findById(examId).orElseThrow(ExamService::notFound);
			// Tạo bản nháp mới là đường sửa duy nhất của bản đã khoá nên quyết định ở mức tài nguyên, không áp
			// thu hẹp theo trạng thái của bản nguồn.
			if (!this.decider.decide(context, exam, null, ExamAction.EDIT_DRAFT).allowed()) {
				throw forbidden("Người gọi không có quyền tạo phiên bản nháp mới cho đề này.");
			}
			ExamVersion source = sourceVersionId == null
					? this.versions.findFirstByExamIdOrderByVersionNumberDesc(examId)
						.orElseThrow(() -> forbidden("Đề chưa có phiên bản nào."))
					: versionOf(examId, sourceVersionId);
			int nextNumber = this.versions.findFirstByExamIdOrderByVersionNumberDesc(examId)
				.orElseThrow(() -> forbidden("Đề chưa có phiên bản nào."))
				.getVersionNumber() + 1;
			ExamVersion created = new ExamVersion();
			created.setId(UUID.randomUUID());
			created.setExamId(examId);
			created.setVersionNumber(nextNumber);
			created.setStatus("draft");
			created.setCreatedBySub(context.sub());
			created.setCreatedAt(now);
			created.setUpdatedAt(now);
			this.versions.save(created);
			saveVersionMirror(created);
			for (ExamItem sourceItem : this.items.findByExamVersionIdOrderByPositionAsc(source.getId())) {
				ExamItem copy = new ExamItem();
				copy.setId(UUID.randomUUID());
				copy.setExamVersionId(created.getId());
				copy.setExamId(examId);
				copy.setQuestionVersionId(sourceItem.getQuestionVersionId());
				copy.setPosition(sourceItem.getPosition());
				copy.setScore(sourceItem.getScore());
				copy.setCreatedAt(now);
				copy.setUpdatedAt(now);
				this.items.save(copy);
			}
			exam.setUpdatedAt(now);
			return created.getId();
		});
		return readView(context, partition, examId, newVersionId);
	}

	public ExamView publish(TenantContext context, UUID examId, UUID versionId) {
		String partition = locatePartition(context, examId);
		LoadedData data = this.sessions.inPartition(partition, context.sub(), () -> {
			Exam exam = this.exams.findById(examId).orElseThrow(ExamService::notFound);
			ExamVersion version = versionOf(examId, versionId);
			return new LoadedData(exam, version, this.items.findByExamVersionIdOrderByPositionAsc(version.getId()),
					List.of());
		});
		Decision decision = this.decider.decide(context, data.exam(), data.version(), ExamAction.PUBLISH);
		if (!decision.allowed()) {
			if ("VERSION_NOT_DRAFT".equals(decision.reasonCode())) {
				throw stateConflict();
			}
			throw forbidden("Người gọi không có quyền phát hành phiên bản đề này.");
		}
		if (data.itemRows().isEmpty()) {
			throw validation("Không phát hành được phiên bản đề chưa có mục nào.");
		}
		// Phát hành đánh giá lại quyền dùng-trong-đề trên từng thành phần tại đúng thời điểm ghi: quyền đã có
		// khi soạn có thể đã mất, và một thành phần không qua được thì toàn bộ việc phát hành bị chặn trước khi
		// bất kỳ hàng snapshot nào được ghi.
		List<QuestionVersionInfo> infos = new ArrayList<>();
		for (ExamItem item : data.itemRows()) {
			QuestionVersionInfo info = this.questionVersions.find(item.getQuestionVersionId())
				.orElseThrow(() -> forbidden("Phiên bản câu " + item.getQuestionVersionId() + " của mục tại vị trí "
						+ item.getPosition()
						+ " không còn tra cứu được nên không đánh giá lại được quyền dùng khi phát hành."));
			if (!this.decider.decideOnQuestion(context, info, ExamAction.USE).allowed()) {
				throw forbidden("Không còn quyền dùng câu tại vị trí " + item.getPosition() + " (phiên bản câu "
						+ item.getQuestionVersionId() + ") nên không phát hành được.");
			}
			infos.add(info);
		}
		OffsetDateTime now = OffsetDateTime.now();
		this.sessions.inPartition(partition, context.sub(), () -> {
			ExamVersion version = versionOf(examId, versionId);
			if (!"draft".equals(version.getStatus())) {
				throw stateConflict();
			}
			List<ExamItemSnapshot> frozen = new ArrayList<>();
			for (int i = 0; i < data.itemRows().size(); i++) {
				ExamItem item = data.itemRows().get(i);
				QuestionVersionInfo info = infos.get(i);
				ExamItemSnapshot snapshot = new ExamItemSnapshot();
				snapshot.setId(UUID.randomUUID());
				snapshot.setExamItemId(item.getId());
				snapshot.setExamVersionId(version.getId());
				snapshot.setQuestionVersionId(item.getQuestionVersionId());
				snapshot.setQuestionType(info.questionType());
				snapshot.setPosition(item.getPosition());
				snapshot.setScore(item.getScore());
				snapshot.setContent(this.objectMapper.writeValueAsString(info.content()));
				snapshot.setCreatedAt(now);
				snapshot.setUpdatedAt(now);
				frozen.add(snapshot);
			}
			this.snapshots.saveAll(frozen);
			version.setStatus("published");
			version.setPublishedAt(now);
			version.setLockedAt(now);
			version.setContentHash(contentHash(frozen));
			version.setUpdatedAt(now);
			// Phát hành là điểm đóng băng của tập mục: hàng lõi của phiên bản chuyển trạng thái cùng lúc và
			// phụ thuộc tới từng phiên bản câu thành phần được ghi đúng tại đây, không ghi lúc soạn vì tập mục
			// của bản nháp còn thay đổi được và chưa bị đóng băng.
			ResourceVersion mirror = this.coreVersions.findByIdAndResourceId(version.getId(), examId)
				.orElseThrow(() -> new IllegalStateException(
						"Phiên bản đề chưa có hàng resource_version tương ứng ở lõi quyền."));
			mirror.setStatus(ResourceVersion.STATUS_PUBLISHED);
			mirror.setLockedAt(now);
			mirror.setPublishedAt(now);
			mirror.setContentHash(version.getContentHash());
			mirror.setUpdatedAt(now);
			for (int i = 0; i < data.itemRows().size(); i++) {
				ResourceDependency dependency = new ResourceDependency();
				dependency.setId(UUID.randomUUID());
				dependency.setResourceId(examId);
				dependency.setResourceVersionId(version.getId());
				dependency.setDependsOnResourceId(infos.get(i).questionId());
				dependency.setDependsOnVersionId(data.itemRows().get(i).getQuestionVersionId());
				dependency.setCreatedAt(now);
				dependency.setUpdatedAt(now);
				this.dependencies.save(dependency);
			}
			return null;
		});
		return readView(context, partition, examId, versionId);
	}

	private ExamView readView(TenantContext context, String partition, UUID examId, UUID versionId) {
		LoadedData data = this.sessions.inPartition(partition, context.sub(), () -> {
			Exam exam = this.exams.findById(examId).orElseThrow(ExamService::notFound);
			ExamVersion version = resolveVersion(examId, versionId);
			List<ExamItem> itemRows = this.items.findByExamVersionIdOrderByPositionAsc(version.getId());
			List<ExamItemSnapshot> snapshotRows = "published".equals(version.getStatus())
					? this.snapshots.findByExamVersionIdOrderByPositionAsc(version.getId()) : List.of();
			return new LoadedData(exam, version, itemRows, snapshotRows);
		});
		Exam exam = data.exam();
		ExamVersion version = data.version();
		if (!this.decider.decide(context, exam, version, ExamAction.VIEW_CONTENT).allowed()) {
			throw forbidden("Người gọi không có quyền xem nội dung của phiên bản đề này.");
		}
		boolean showAnswers = this.decider.decide(context, exam, version, ExamAction.VIEW_ANSWER).allowed();
		List<ItemView> itemViews = new ArrayList<>();
		if ("published".equals(version.getStatus())) {
			for (ExamItemSnapshot snapshot : data.snapshotRows()) {
				JsonNode content = this.objectMapper.readTree(snapshot.getContent());
				// Sau khi đóng băng, hiển thị đáp án chỉ còn qua cổng view_answer của chính phiên bản đề: quyền
				// trên câu nguồn không cần còn nữa và cũng không cấp thêm được gì.
				itemViews.add(new ItemView(snapshot.getExamItemId(), snapshot.getQuestionVersionId(),
						snapshot.getQuestionType(), snapshot.getPosition(), snapshot.getScore(),
						showAnswers ? content : stripAnswers(content, snapshot.getQuestionType())));
			}
		}
		else {
			for (ExamItem item : data.itemRows()) {
				Optional<QuestionVersionInfo> info = this.questionVersions.find(item.getQuestionVersionId());
				if (info.isEmpty()) {
					itemViews.add(new ItemView(item.getId(), item.getQuestionVersionId(), null, item.getPosition(),
							item.getScore(), null));
					continue;
				}
				// Bản nháp còn tham chiếu sống nên đáp án của một mục chỉ hiện khi có view_answer trên cả phiên
				// bản đề lẫn đúng phiên bản câu của mục đó.
				boolean showItemAnswers = showAnswers
						&& this.decider.decideOnQuestion(context, info.get(), ExamAction.VIEW_ANSWER).allowed();
				JsonNode content = info.get().content();
				itemViews.add(new ItemView(item.getId(), item.getQuestionVersionId(), info.get().questionType(),
						item.getPosition(), item.getScore(),
						showItemAnswers ? content : stripAnswers(content, info.get().questionType())));
			}
		}
		return new ExamView(exam.getId(), "exam", exam.getWorkspaceKind(), exam.getOwnerType(), exam.getOwnerUserSub(),
				exam.getOwnerOrganizationId(), exam.getCollectionId(), exam.getCustodyKind(), exam.getTitle(),
				version.getId(), version.getVersionNumber(), version.getStatus(), version.getLockedAt(),
				version.getContentHash(), version.getLockVersion(), itemViews);
	}

	// Kiểm tra hợp lệ và quyền dùng-trong-đề cho từng mục của một lượt sửa bản nháp: một mục không qua được thì
	// toàn bộ lượt sửa bị từ chối trước khi bất kỳ hàng nào được ghi, và lỗi nêu rõ vị trí cùng phiên bản câu bị
	// chặn để người soạn biết phải thay thành phần nào.
	private void checkItemsUsable(TenantContext context, List<ItemInput> itemInputs) {
		if (itemInputs == null) {
			throw validation("Thiếu danh sách mục của bản nháp.");
		}
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < itemInputs.size(); i++) {
			ItemInput input = itemInputs.get(i);
			int position = i + 1;
			if (input.questionVersionId() == null) {
				throw validation("Mục tại vị trí " + position + " thiếu định danh phiên bản câu.");
			}
			if (!seen.add(input.questionVersionId())) {
				throw validation("Phiên bản câu " + input.questionVersionId()
						+ " xuất hiện nhiều lần trong cùng một bản nháp.");
			}
			if (input.score() == null || input.score().signum() <= 0) {
				throw validation("Điểm của mục tại vị trí " + position + " phải lớn hơn 0.");
			}
			QuestionVersionInfo info = this.questionVersions.find(input.questionVersionId())
				.orElseThrow(() -> validation("Phiên bản câu " + input.questionVersionId() + " của mục tại vị trí "
						+ position + " không tồn tại."));
			if (!this.decider.decideOnQuestion(context, info, ExamAction.USE).allowed()) {
				throw forbidden("Không có quyền dùng câu tại vị trí " + position + " (phiên bản câu "
						+ input.questionVersionId() + ") trong đề.");
			}
		}
	}

	// Một đề chỉ nằm ở một phân vùng; người gọi định vị được đề ở các phân vùng mình đại diện: phân vùng cá
	// nhân, Organization của trang khi đang là thành viên, và phân vùng hệ thống khi là platform-admin.
	private List<String> candidatePartitions(TenantContext context) {
		LinkedHashSet<String> partitions = new LinkedHashSet<>();
		partitions.add("user:" + context.sub());
		if (context.organizationId() != null) {
			partitions.add(context.organizationId());
		}
		if (context.platformAdmin()) {
			partitions.add("system");
		}
		return List.copyOf(partitions);
	}

	private String locatePartition(TenantContext context, UUID examId) {
		for (String partition : candidatePartitions(context)) {
			boolean present = this.sessions.inPartition(partition, context.sub(),
					() -> this.exams.existsById(examId));
			if (present) {
				return partition;
			}
		}
		throw notFound();
	}

	private ExamVersion resolveVersion(UUID examId, UUID versionId) {
		if (versionId == null) {
			return this.versions.findFirstByExamIdOrderByVersionNumberDesc(examId)
				.orElseThrow(() -> forbidden("Đề chưa có phiên bản nào."));
		}
		return versionOf(examId, versionId);
	}

	// Quan hệ phiên bản–đề không khớp trả 403 thay vì 404 và không ghi dữ kiện: không xác nhận cho người gọi
	// biết phiên bản đó thuộc về đề nào khác.
	private ExamVersion versionOf(UUID examId, UUID versionId) {
		return this.versions.findByExamIdAndId(examId, versionId)
			.orElseThrow(() -> forbidden("Phiên bản được gọi không thuộc về đề này."));
	}

	// Hàng lõi của đề mang đúng các trường chủ sở hữu và workspace của hàng đề; khoá phân vùng của hàng lõi do
	// Hibernate đóng dấu từ tenant của phiên phân vùng đang chạy, trùng phân vùng của chính đề.
	private void saveResourceMirror(Exam exam) {
		AccessResource mirror = new AccessResource();
		mirror.setId(exam.getId());
		mirror.setResourceType("exam");
		mirror.setWorkspaceKind(exam.getWorkspaceKind());
		mirror.setOwnerType(exam.getOwnerType());
		mirror.setOwnerUserSub(exam.getOwnerUserSub());
		mirror.setOwnerOrganizationId(exam.getOwnerOrganizationId());
		mirror.setCollectionId(exam.getCollectionId());
		mirror.setCustodyKind(exam.getCustodyKind());
		mirror.setCreatedBySub(exam.getCreatedBySub());
		mirror.setCreatedAt(exam.getCreatedAt());
		mirror.setUpdatedAt(exam.getUpdatedAt());
		this.resources.save(mirror);
	}

	private void saveVersionMirror(ExamVersion version) {
		ResourceVersion mirror = new ResourceVersion();
		mirror.setId(version.getId());
		mirror.setResourceId(version.getExamId());
		mirror.setVersionNumber(version.getVersionNumber());
		mirror.setStatus(version.getStatus());
		mirror.setCreatedBySub(version.getCreatedBySub());
		mirror.setCreatedAt(version.getCreatedAt());
		mirror.setUpdatedAt(version.getUpdatedAt());
		this.coreVersions.save(mirror);
	}

	private static JsonNode stripAnswers(JsonNode content, String questionType) {
		if (!(content instanceof ObjectNode object)) {
			return content;
		}
		ObjectNode copy = object.deepCopy();
		if ("single_choice".equals(questionType)) {
			copy.remove("correctChoiceId");
			copy.remove("solution");
		}
		else if ("essay_text".equals(questionType)) {
			copy.remove("rubric");
			copy.remove("solution");
		}
		return copy;
	}

	// content_hash băm trên chính các văn bản snapshot đã ghi (theo thứ tự mục, kèm định danh phiên bản câu,
	// điểm và thứ tự của từng mục): bản đóng băng đã ghi là dạng biểu diễn bất biến dùng để băm, nên kiểm chứng
	// về sau chỉ cần băm lại đúng các văn bản đã lưu thay vì serialize lại nội dung câu tại thời điểm kiểm.
	private static String contentHash(List<ExamItemSnapshot> frozen) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		for (ExamItemSnapshot snapshot : frozen) {
			String line = snapshot.getPosition() + ":" + snapshot.getQuestionVersionId() + ":"
					+ snapshot.getScore().toPlainString() + ":" + snapshot.getContent() + "\n";
			digest.update(line.getBytes(StandardCharsets.UTF_8));
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static ExamException notFound() {
		return new ExamException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
				"Không tìm thấy đề trong các phân vùng người gọi đại diện.");
	}

	private static ExamException forbidden(String message) {
		return new ExamException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", message);
	}

	private static ExamException validation(String message) {
		return new ExamException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
	}

	private static ExamException lockedVersion() {
		return new ExamException(HttpStatus.CONFLICT, "VERSION_LOCKED",
				"Phiên bản đề đã phát hành hoặc đã khoá nên không sửa trực tiếp được; tạo phiên bản nháp mới để sửa.");
	}

	private static ExamException stateConflict() {
		return new ExamException(HttpStatus.CONFLICT, "STATE_CONFLICT",
				"Chỉ phát hành được phiên bản đề đang là bản nháp.");
	}

}
