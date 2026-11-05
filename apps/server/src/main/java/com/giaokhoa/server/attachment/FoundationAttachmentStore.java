package com.giaokhoa.server.attachment;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.giaokhoa.server.access.AccessResource;
import com.giaokhoa.server.access.AccessResourceRepository;
import com.giaokhoa.server.access.PartitionSessions;
import com.giaokhoa.server.access.ResourceVersion;
import com.giaokhoa.server.access.ResourceVersionRepository;
import com.giaokhoa.server.files.BlobStorage;
import com.giaokhoa.server.files.FileBlob;
import com.giaokhoa.server.files.FileBlobRepository;
import com.giaokhoa.server.files.FileLink;
import com.giaokhoa.server.files.FileLinkRepository;
import com.giaokhoa.server.files.UploadSession;
import com.giaokhoa.server.files.UploadSessionRepository;
import com.giaokhoa.server.files.UuidV7;
import com.giaokhoa.server.question.Question;
import com.giaokhoa.server.question.QuestionBank;
import com.giaokhoa.server.question.QuestionBankRepository;
import com.giaokhoa.server.question.QuestionRepository;
import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.QuestionVersionRepository;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Bản cài đặt thật của cổng kho đính kèm trên nền tệp L1: phiên, liên kết và blob ghi vào đúng ba bảng
 * {@code upload_session}, {@code file_link}, {@code file_blob} của nền, byte đi qua cổng {@link BlobStorage}
 * vào kho riêng tư (vùng tạm và vùng blob đã chốt tách nhau, khoá do server sinh). Hai bảng phiên và liên kết
 * của nền có khoá ngoại tới tài nguyên và phiên bản ở lõi quyền, nên trước khi ghi phiên đầu tiên cho một phiên
 * bản câu, bản cài đặt này bảo đảm hàng gương của ngân hàng, câu và phiên bản đã có ở lõi quyền — đúng khuôn
 * hàng gương mà đường đọc câu hỏi của feat đề đã dựng khi dùng một phiên bản câu.
 *
 * <p>
 * shortcut: đích khoá của phiên (vị trí và khối) khi phiên còn dở chỉ giữ trong bộ nhớ của bean này, vì bảng
 * phiên của nền không có cột cho chúng — đích chỉ thành dữ kiện bền khi liên kết được tạo lúc hoàn tất. Server
 * khởi động lại giữa chừng thì phiên dở còn hàng và byte tạm vẫn còn, nhưng mất đích khoá nên không hoàn tất
 * tiếp được (tầng dịch vụ nhận lỗi xung đột sạch thay vì lỗi dữ kiện); nâng cấp khi nền tệp có cột đích cho
 * phiên hoặc hợp đồng phiên của nền nhận đích lúc mở phiên.
 * </p>
 */
@Component
class FoundationAttachmentStore implements AttachmentStore {

	private final UploadSessionRepository sessions;

	private final FileLinkRepository links;

	private final FileBlobRepository blobs;

	private final BlobStorage storage;

	private final PartitionSessions partitions;

	private final QuestionRepository questions;

	private final QuestionVersionRepository versions;

	private final QuestionBankRepository banks;

	private final AccessResourceRepository resources;

	private final ResourceVersionRepository coreVersions;

	private final long maxBytes;

	private final long maxPixels;

	private final Map<UUID, String[]> inFlightTargets = new ConcurrentHashMap<>();

	private final Map<UUID, String> stagingKeys = new ConcurrentHashMap<>();

	FoundationAttachmentStore(UploadSessionRepository sessions, FileLinkRepository links, FileBlobRepository blobs,
			BlobStorage storage, PartitionSessions partitions, QuestionRepository questions,
			QuestionVersionRepository versions, QuestionBankRepository banks, AccessResourceRepository resources,
			ResourceVersionRepository coreVersions, @Value("${app.attachments.max-bytes:5242880}") long maxBytes,
			@Value("${app.attachments.max-pixels:20000000}") long maxPixels) {
		this.sessions = sessions;
		this.links = links;
		this.blobs = blobs;
		this.storage = storage;
		this.partitions = partitions;
		this.questions = questions;
		this.versions = versions;
		this.banks = banks;
		this.resources = resources;
		this.coreVersions = coreVersions;
		this.maxBytes = maxBytes;
		this.maxPixels = maxPixels;
	}

	@Override
	public Session saveSession(Session session) {
		if (session.position() != null) {
			this.inFlightTargets.put(session.id(), new String[] { session.position(), session.blockId() });
		}
		String partition = "user:" + session.uploaderSub();
		boolean exists = this.partitions.inPartition(partition, session.uploaderSub(),
				() -> this.sessions.findById(session.id()).isPresent());
		if (!exists) {
			ensureCoreMirror(session.questionId(), session.versionId());
		}
		OffsetDateTime now = OffsetDateTime.now();
		this.partitions.inPartition(partition, session.uploaderSub(), () -> {
			UploadSession row = this.sessions.findById(session.id()).orElseGet(UploadSession::new);
			row.setId(session.id());
			row.setUploaderSub(session.uploaderSub());
			row.setTargetResourceId(session.questionId());
			row.setTargetVersionId(session.versionId());
			row.setDisplayName(session.displayName());
			row.setStatus(session.status());
			row.setBlobId(session.blobId());
			row.setLinkId(session.linkId());
			row.setExpiresAt(session.expiresAt());
			if (UploadSession.STATUS_READY.equals(session.status()) && session.stagedChecksum() != null) {
				// Checksum của byte đã kiểm chứng được đóng băng ngay tại bước tải lên, gắn kèm khoá tạm trong
				// cùng cột staging_key (dạng "<khoá>#<checksum>"): bước chốt đối chiếu byte ở vùng tạm với
				// đúng checksum này, nên byte bị thay ở vùng tạm sau bước kiểm chứng không lọt qua được.
				String base = baseKey(
						(row.getStagingKey() != null) ? row.getStagingKey() : this.stagingKeys.get(session.id()));
				if (base != null) {
					row.setStagingKey(base + "#" + session.stagedChecksum());
				}
			}
			if (row.getCreatedAt() == null) {
				row.setCreatedAt(now);
			}
			row.setUpdatedAt(now);
			this.sessions.save(row);
		});
		return session;
	}

	@Override
	public Optional<Session> findSession(UUID sessionId) {
		for (String partition : candidatePartitions()) {
			Optional<Session> found = this.partitions.inPartition(partition, actorSub(),
					() -> this.sessions.findById(sessionId).map(this::toSession));
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	@Override
	public List<Session> findSessionsForVersion(UUID versionId) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>(candidatePartitions());
		ownerPartitionOfVersion(versionId).ifPresent(candidates::add);
		Map<UUID, Session> byId = new LinkedHashMap<>();
		for (String partition : candidates) {
			List<Session> found = this.partitions.inPartition(partition, actorSub(),
					() -> this.sessions.findByTargetVersionId(versionId).stream().map(this::toSession).toList());
			for (Session session : found) {
				byId.putIfAbsent(session.id(), session);
			}
		}
		return List.copyOf(byId.values());
	}

	@Override
	public Blob saveBlob(Blob blob, byte[] bytes) {
		String uploader = actorSub();
		String storageKey = "blobs/" + blob.id();
		String stagingKey = this.storage.stage(blob.id(), bytes);
		this.storage.promote(stagingKey, storageKey);
		OffsetDateTime now = OffsetDateTime.now();
		this.partitions.inPartition("user:" + uploader, uploader, () -> {
			FileBlob row = new FileBlob();
			row.setId(blob.id());
			row.setStorageKey(storageKey);
			row.setContentType(blob.contentType());
			row.setSizeBytes(blob.sizeBytes());
			row.setChecksumSha256(blob.checksumSha256());
			row.setWidth(blob.width());
			row.setHeight(blob.height());
			row.setStatus(blob.status());
			row.setUploadedBySub(uploader);
			row.setCreatedAt(now);
			row.setUpdatedAt(now);
			this.blobs.save(row);
		});
		return blob;
	}

	@Override
	public Optional<Blob> findBlob(UUID blobId) {
		for (String partition : candidatePartitions()) {
			Optional<Blob> found = this.partitions.inPartition(partition, actorSub(),
					() -> this.blobs.findById(blobId).map(FoundationAttachmentStore::toBlob));
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	@Override
	public UUID rehomeBlob(UUID blobId, String fromPartitionKey, String toPartitionKey) {
		if (fromPartitionKey == null || fromPartitionKey.equals(toPartitionKey)) {
			return blobId;
		}
		FileBlob source = this.partitions.inPartition(fromPartitionKey, actorSub(),
				() -> this.blobs.findById(blobId).orElse(null));
		if (source == null) {
			for (String partition : candidatePartitions()) {
				source = this.partitions.inPartition(partition, actorSub(),
						() -> this.blobs.findById(blobId).orElse(null));
				if (source != null) {
					break;
				}
			}
		}
		if (source == null) {
			return blobId;
		}
		byte[] bytes = this.storage.read(source.getStorageKey());
		UUID newId = UuidV7.next();
		String stagingKey = this.storage.stage(newId, bytes);
		try {
			OffsetDateTime now = OffsetDateTime.now();
			FileBlob copy = new FileBlob();
			copy.setId(newId);
			copy.setStorageKey("blobs/" + newId);
			copy.setContentType(source.getContentType());
			copy.setSizeBytes(source.getSizeBytes());
			copy.setChecksumSha256(source.getChecksumSha256());
			copy.setWidth(source.getWidth());
			copy.setHeight(source.getHeight());
			copy.setStatus(source.getStatus());
			copy.setUploadedBySub(actorSub());
			copy.setCreatedAt(now);
			copy.setUpdatedAt(now);
			this.partitions.inPartition(toPartitionKey, actorSub(), () -> {
				this.storage.promote(stagingKey, "blobs/" + newId);
				this.blobs.save(copy);
			});
			return newId;
		}
		catch (RuntimeException ex) {
			this.storage.deleteStaged(stagingKey);
			throw ex;
		}
	}

	@Override
	public Optional<byte[]> readBlob(UUID blobId) {
		for (String partition : candidatePartitions()) {
			Optional<FileBlob> row = this.partitions.inPartition(partition, actorSub(),
					() -> this.blobs.findById(blobId));
			if (row.isPresent()) {
				try {
					return Optional.of(this.storage.read(row.get().getStorageKey()));
				}
				catch (RuntimeException ex) {
					return Optional.empty();
				}
			}
		}
		return Optional.empty();
	}

	@Override
	public void stage(UUID sessionId, byte[] bytes) {
		String key = this.storage.stage(sessionId, bytes);
		this.stagingKeys.put(sessionId, key);
		updateStagingKey(sessionId, key);
	}

	@Override
	public Optional<byte[]> staged(UUID sessionId) {
		String key = stagingKey(sessionId);
		if (key == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(this.storage.readStaged(key));
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	@Override
	public void dropStaged(UUID sessionId) {
		String key = this.stagingKeys.remove(sessionId);
		if (key == null) {
			key = stagingKey(sessionId);
		}
		if (key != null) {
			this.storage.deleteStaged(key);
		}
		updateStagingKey(sessionId, null);
	}

	@Override
	public Link saveLink(Link link) {
		if (link.position() == null) {
			throw AttachmentException.conflict("STATE_CONFLICT",
					"Phiên tải lên đã mất đích khoá nên không gắn tiếp được; hãy mở phiên tải lên mới.");
		}
		ensureCoreMirror(link.questionId(), link.versionId());
		String partition = this.questions.findById(link.questionId()).map((q) -> "user:" + q.getOwnerSub())
				.orElseGet(() -> "user:" + actorSub());
		OffsetDateTime now = OffsetDateTime.now();
		String createdBy = actorSub();
		this.partitions.inPartition(partition, createdBy, () -> {
			FileLink row = new FileLink();
			row.setId(link.id());
			row.setResourceId(link.questionId());
			row.setResourceVersionId(link.versionId());
			row.setPosition(link.position());
			row.setBlockId(link.blockId());
			row.setBlobId(link.blobId());
			row.setDisplayName(link.displayName());
			row.setSessionId(link.sessionId());
			row.setCreatedBySub(createdBy);
			row.setCreatedAt(now);
			row.setUpdatedAt(now);
			this.links.save(row);
		});
		return link;
	}

	@Override
	public Optional<Link> findLink(UUID linkId) {
		for (String partition : candidatePartitions()) {
			Optional<Link> found = this.partitions.inPartition(partition, actorSub(),
					() -> this.links.findById(linkId).map(FoundationAttachmentStore::toLink));
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	@Override
	public List<Link> findLinksForVersion(UUID versionId) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>(candidatePartitions());
		ownerPartitionOfVersion(versionId).ifPresent(candidates::add);
		Map<UUID, Link> byId = new LinkedHashMap<>();
		for (String partition : candidates) {
			List<FileLink> rows = this.partitions.inPartition(partition, actorSub(),
					() -> this.links.findByResourceVersionIdOrderByCreatedAtAsc(versionId));
			for (FileLink row : rows) {
				byId.putIfAbsent(row.getId(), toLink(row));
			}
		}
		return List.copyOf(byId.values());
	}

	// Hàng gương ở lõi quyền cho ngân hàng, câu và phiên bản: chỉ tạo khi chưa có, và làm tươi trạng thái của
	// phiên bản theo đúng dữ kiện hiện hành của feat câu hỏi — cùng khuôn hàng gương của đường đọc câu hỏi ở
	// feat đề, để khoá ngoại của bảng phiên và bảng liên kết của nền tệp có đích thật mà trỏ tới.
	private void ensureCoreMirror(UUID questionId, UUID versionId) {
		Question question = this.questions.findById(questionId).orElse(null);
		QuestionVersion version = (question != null)
				? this.versions.findByIdAndQuestionId(versionId, questionId).orElse(null)
				: null;
		QuestionBank bank = (question != null) ? this.banks.findById(question.getBankId()).orElse(null) : null;
		if (question == null || version == null || bank == null) {
			return;
		}
		String partition = "user:" + question.getOwnerSub();
		TenantContext context = TenantContexts.current();
		String actorSub = (context != null) ? context.sub() : question.getOwnerSub();
		this.partitions.inPartition(partition, actorSub, () -> {
			if (this.resources.findById(bank.getId()).isEmpty()) {
				AccessResource bankResource = new AccessResource();
				bankResource.setId(bank.getId());
				bankResource.setResourceType("question_bank");
				bankResource.setWorkspaceKind(AccessResource.WORKSPACE_PERSONAL);
				bankResource.setOwnerType(AccessResource.OWNER_USER);
				bankResource.setOwnerUserSub(bank.getOwnerSub());
				bankResource.setCustodyKind(AccessResource.CUSTODY_OWNED);
				bankResource.setCreatedBySub(bank.getOwnerSub());
				bankResource.setCreatedAt(bank.getCreatedAt());
				bankResource.setUpdatedAt(bank.getUpdatedAt());
				this.resources.save(bankResource);
			}
			if (this.resources.findById(question.getId()).isEmpty()) {
				AccessResource questionResource = new AccessResource();
				questionResource.setId(question.getId());
				questionResource.setResourceType("question");
				questionResource.setWorkspaceKind(AccessResource.WORKSPACE_PERSONAL);
				questionResource.setOwnerType(AccessResource.OWNER_USER);
				questionResource.setOwnerUserSub(question.getOwnerSub());
				questionResource.setCollectionId(bank.getId());
				questionResource.setCustodyKind(AccessResource.CUSTODY_OWNED);
				questionResource.setInheritedUsageLimits(question.getUsageLimits());
				questionResource.setSourceResourceId(question.getSourceQuestionId());
				questionResource.setSourceVersionId(question.getSourceVersionId());
				questionResource.setCreatedBySub(question.getOwnerSub());
				questionResource.setCreatedAt(question.getCreatedAt());
				questionResource.setUpdatedAt(question.getUpdatedAt());
				this.resources.save(questionResource);
			}
			ResourceVersion mirror = this.coreVersions.findById(version.getId()).orElse(null);
			if (mirror == null) {
				mirror = new ResourceVersion();
				mirror.setId(version.getId());
				mirror.setResourceId(question.getId());
				mirror.setVersionNumber(version.getVersionNumber());
				mirror.setCreatedBySub(version.getOwnerSub());
				mirror.setCreatedAt(version.getCreatedAt());
			}
			mirror.setStatus(version.getStatus());
			mirror.setContentHash(version.getContentHash());
			mirror.setLockedAt(version.getLockedAt());
			mirror.setPublishedAt(
					QuestionVersion.STATUS_PUBLISHED.equals(version.getStatus()) ? version.getLockedAt() : null);
			mirror.setUpdatedAt(version.getUpdatedAt());
			this.coreVersions.save(mirror);
		});
	}

	private Session toSession(UploadSession row) {
		String position = null;
		String blockId = null;
		String[] inFlight = this.inFlightTargets.get(row.getId());
		if (inFlight != null) {
			position = inFlight[0];
			blockId = inFlight[1];
		}
		String contentType = null;
		Long sizeBytes = null;
		Integer width = null;
		Integer height = null;
		String checksum = null;
		if (row.getBlobId() != null) {
			FileBlob blob = this.blobs.findById(row.getBlobId()).orElse(null);
			if (blob != null) {
				contentType = blob.getContentType();
				sizeBytes = blob.getSizeBytes();
				width = blob.getWidth();
				height = blob.getHeight();
				checksum = blob.getChecksumSha256();
			}
		}
		if (row.getLinkId() != null) {
			FileLink link = this.links.findById(row.getLinkId()).orElse(null);
			if (link != null) {
				position = link.getPosition();
				blockId = link.getBlockId();
			}
		}
		if (UploadSession.STATUS_READY.equals(row.getStatus())) {
			// Loại, kích thước và số điểm ảnh của phiên sẵn sàng không có cột riêng ở bảng phiên của nền: suy
			// lại từ byte đang ở vùng tạm. Riêng checksum thì lấy bản đã đóng băng lúc tải lên (gắn kèm khoá
			// tạm), không suy từ byte hiện tại — byte ở vùng tạm bị thay sau bước kiểm chứng phải làm bước
			// chốt lệch checksum và bị từ chối, đúng bất biến của cổng kho.
			checksum = frozenChecksum(row.getStagingKey());
			byte[] staged = stagedBytes(row);
			if (staged != null) {
				try {
					ImageProcessing.Processed processed = ImageProcessing.process(staged, this.maxBytes,
							this.maxPixels);
					contentType = processed.contentType();
					sizeBytes = (long) processed.bytes().length;
					width = processed.width();
					height = processed.height();
					if (checksum == null) {
						checksum = processed.checksumSha256();
					}
				}
				catch (AttachmentException ex) {
					contentType = null;
					sizeBytes = null;
					width = null;
					height = null;
					checksum = null;
				}
			}
		}
		return new Session(row.getId(), row.getUploaderSub(), row.getTargetResourceId(), row.getTargetVersionId(),
				position, blockId, row.getDisplayName(), row.getStatus(), contentType, sizeBytes, width, height,
				checksum, row.getBlobId(), row.getLinkId(), row.getExpiresAt());
	}

	private byte[] stagedBytes(UploadSession row) {
		String key = (row.getStagingKey() != null) ? baseKey(row.getStagingKey())
				: this.stagingKeys.get(row.getId());
		if (key == null) {
			return null;
		}
		try {
			return this.storage.readStaged(key);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private String stagingKey(UUID sessionId) {
		String key = this.stagingKeys.get(sessionId);
		if (key != null) {
			return key;
		}
		for (String partition : candidatePartitions()) {
			Optional<UploadSession> row = this.partitions.inPartition(partition, actorSub(),
					() -> this.sessions.findById(sessionId));
			if (row.isPresent() && row.get().getStagingKey() != null) {
				String base = baseKey(row.get().getStagingKey());
				this.stagingKeys.put(sessionId, base);
				return base;
			}
		}
		return null;
	}

	private void updateStagingKey(UUID sessionId, String key) {
		for (String partition : candidatePartitions()) {
			boolean updated = this.partitions.inPartition(partition, actorSub(), () -> {
				Optional<UploadSession> row = this.sessions.findById(sessionId);
				if (row.isEmpty()) {
					return false;
				}
				String existing = row.get().getStagingKey();
				String value = key;
				if (key != null && existing != null && key.equals(baseKey(existing))
						&& frozenChecksum(existing) != null) {
					// Ghi lại byte vào đúng khoá tạm cũ thì giữ nguyên checksum đã đóng băng của khoá đó; chỉ
					// bước lưu phiên sau xử lý mới đóng băng checksum mới.
					value = existing;
				}
				row.get().setStagingKey(value);
				row.get().setUpdatedAt(OffsetDateTime.now());
				this.sessions.save(row.get());
				return true;
			});
			if (updated) {
				return;
			}
		}
	}

	private static String baseKey(String storedStagingKey) {
		if (storedStagingKey == null) {
			return null;
		}
		int separator = storedStagingKey.indexOf('#');
		return (separator >= 0) ? storedStagingKey.substring(0, separator) : storedStagingKey;
	}

	private static String frozenChecksum(String storedStagingKey) {
		if (storedStagingKey == null) {
			return null;
		}
		int separator = storedStagingKey.indexOf('#');
		return (separator >= 0) ? storedStagingKey.substring(separator + 1) : null;
	}

	private Optional<String> ownerPartitionOfVersion(UUID versionId) {
		return this.versions.findById(versionId)
				.flatMap((version) -> this.questions.findById(version.getQuestionId()))
				.map((question) -> "user:" + question.getOwnerSub());
	}

	private List<String> candidatePartitions() {
		LinkedHashSet<String> partitions = new LinkedHashSet<>();
		TenantContext context = TenantContexts.current();
		if (context != null) {
			if (context.partitionKey() != null) {
				partitions.add(context.partitionKey());
			}
			partitions.add("user:" + context.sub());
		}
		return List.copyOf(partitions);
	}

	private static String actorSub() {
		TenantContext context = TenantContexts.current();
		return (context != null) ? context.sub() : "system";
	}

	private static Blob toBlob(FileBlob row) {
		return new Blob(row.getId(), row.getContentType(), row.getSizeBytes(), row.getChecksumSha256(),
				(row.getWidth() != null) ? row.getWidth() : 0, (row.getHeight() != null) ? row.getHeight() : 0,
				row.getStatus());
	}

	private static Link toLink(FileLink row) {
		return new Link(row.getId(), row.getResourceId(), row.getResourceVersionId(), row.getPosition(),
				row.getBlockId(), row.getBlobId(), row.getDisplayName(), row.getSessionId());
	}

}
