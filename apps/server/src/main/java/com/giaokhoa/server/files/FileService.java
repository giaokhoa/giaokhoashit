package com.giaokhoa.server.files;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.giaokhoa.server.access.AccessAction;
import com.giaokhoa.server.access.AccessDecisionService;
import com.giaokhoa.server.access.ResourceGrant;
import com.giaokhoa.server.access.ResourceGrantRepository;
import com.giaokhoa.server.access.AccessDecisionService.Decision;
import com.giaokhoa.server.access.AccessDecisionService.Located;
import com.giaokhoa.server.access.AccessException;
import com.giaokhoa.server.access.PartitionSessions;
import com.giaokhoa.server.files.ImageVerifier.VerifiedImage;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Nghiệp vụ của nền tệp: phiên tải lên năm bước (xin phiên, nhận vào vùng tạm, kiểm chứng nội dung thật, chốt
 * blob bất biến, gắn vào bản nháp có kiểm lại quyền), liên kết tệp theo ngữ cảnh, đường đọc kiểm quyền tại thời
 * điểm truy cập và cách ly blob nguy hiểm phát hiện muộn. Mọi quyết định quyền đi qua
 * {@link AccessDecisionService} của lõi quyền trên đúng tài nguyên và phiên bản đích; người tải lên chỉ là truy
 * vết, không phải nguồn quyền.
 */
@Service
public class FileService {

	public record CreateSessionRequest(UUID resourceId, UUID versionId, String displayName) {
	}

	public record AttachRequest(UUID resourceId, UUID versionId, String position, String blockId) {
	}

	public record AttachBlobRequest(UUID blobId, UUID resourceId, UUID versionId, String position, String blockId,
			String displayName) {
	}

	public record SessionView(UUID id, String state, UUID targetResourceId, UUID targetVersionId, String displayName,
			UUID blobId, UUID linkId, OffsetDateTime expiresAt) {
	}

	public record LinkView(UUID id, UUID resourceId, UUID resourceVersionId, String position, String blockId,
			UUID blobId, String displayName, String contentType, long sizeBytes, String state,
			OffsetDateTime createdAt) {
	}

	public record FileContent(byte[] bytes, String contentType, String displayName) {
	}

	private static final Set<String> POSITIONS = Set.of(FileLink.POSITION_CONTENT, FileLink.POSITION_CHOICE,
			FileLink.POSITION_ANSWER, FileLink.POSITION_SOLUTION, FileLink.POSITION_DOCUMENT);

	private final FileBlobRepository blobs;

	private final UploadSessionRepository sessionsRepo;

	private final FileLinkRepository links;

	private final FileAuditLogRepository audit;

	private final AccessDecisionService decisions;

	private final ResourceGrantRepository grants;

	private final PartitionSessions sessions;

	private final BlobStorage storage;

	private final ImageVerifier verifier;

	private final long maxFileSizeBytes;

	private final long maxMegapixels;

	private final long sessionTtlHours;

	private final long maxUserBytes;

	private final long maxWorkspaceBytes;

	public FileService(FileBlobRepository blobs, UploadSessionRepository sessionsRepo, FileLinkRepository links,
			FileAuditLogRepository audit, AccessDecisionService decisions, ResourceGrantRepository grants,
			PartitionSessions sessions,
			BlobStorage storage, ImageVerifier verifier,
			@Value("${app.files.max-file-size-bytes:5242880}") long maxFileSizeBytes,
			@Value("${app.files.max-megapixels:20000000}") long maxMegapixels,
			@Value("${app.files.session-ttl-hours:24}") long sessionTtlHours,
			@Value("${app.files.max-user-bytes:524288000}") long maxUserBytes,
			@Value("${app.files.max-workspace-bytes:5368709120}") long maxWorkspaceBytes) {
		this.blobs = blobs;
		this.sessionsRepo = sessionsRepo;
		this.links = links;
		this.audit = audit;
		this.decisions = decisions;
		this.grants = grants;
		this.sessions = sessions;
		this.storage = storage;
		this.verifier = verifier;
		this.maxFileSizeBytes = maxFileSizeBytes;
		this.maxMegapixels = maxMegapixels;
		this.sessionTtlHours = sessionTtlHours;
		this.maxUserBytes = maxUserBytes;
		this.maxWorkspaceBytes = maxWorkspaceBytes;
	}

	public SessionView createSession(CreateSessionRequest request) {
		TenantContext context = requireContext();
		if (request == null || request.resourceId() == null || request.versionId() == null
				|| request.displayName() == null || request.displayName().isBlank()) {
			throw AccessException.validation("Phiên tải lên cần resourceId, versionId và tên hiển thị của tệp");
		}
		Located located = this.decisions.locate(context, request.resourceId());
		if (located == null) {
			throw AccessException.notFound("Không tìm thấy tài nguyên");
		}
		requireAllowed(this.decisions.decide(context, request.resourceId(), request.versionId(),
				AccessAction.EDIT_DRAFT));
		OffsetDateTime now = OffsetDateTime.now();
		UploadSession session = new UploadSession();
		session.setId(UuidV7.next());
		session.setUploaderSub(context.sub());
		session.setTargetResourceId(request.resourceId());
		session.setTargetVersionId(request.versionId());
		session.setDisplayName(request.displayName());
		session.setStatus(UploadSession.STATUS_OPEN);
		session.setExpiresAt(now.plusHours(this.sessionTtlHours));
		session.setCreatedAt(now);
		session.setUpdatedAt(now);
		UploadSession saved = this.sessions.inPartition(located.partitionKey(), context.sub(),
				() -> this.sessionsRepo.save(session));
		return sessionView(saved);
	}

	public SessionView uploadContent(UUID sessionId, MultipartFile file) {
		TenantContext context = requireContext();
		if (file == null || file.isEmpty()) {
			throw AccessException.validation("Tệp tải lên rỗng");
		}
		LocatedSession located = locateSession(context, sessionId);
		UploadSession snapshot = located.session();
		requireUploader(context, snapshot);
		if (UploadSession.STATUS_READY.equals(snapshot.getStatus())
				|| UploadSession.STATUS_COMPLETED.equals(snapshot.getStatus())) {
			return sessionView(snapshot);
		}
		requireSessionUsable(snapshot);
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			throw AccessException.validation("Không đọc được tệp tải lên");
		}
		String stagingKey = this.storage.stage(sessionId, bytes);
		VerifiedImage image;
		try {
			image = this.verifier.verify(bytes, this.maxFileSizeBytes, this.maxMegapixels);
		}
		catch (RuntimeException ex) {
			this.storage.deleteStaged(stagingKey);
			throw ex;
		}
		// Chỉ byte đã đọc lại từ vùng tạm và khớp checksum với byte đã kiểm chứng mới được chốt: byte ở vùng tạm
		// bị thay sau bước kiểm chứng không lọt vào blob chuẩn.
		byte[] staged = this.storage.readStaged(stagingKey);
		String checksum = sha256(staged);
		if (!checksum.equals(sha256(bytes))) {
			this.storage.deleteStaged(stagingKey);
			throw AccessException.stateConflict("Nội dung ở vùng tạm đã thay đổi sau bước kiểm chứng");
		}
		UUID blobId = UuidV7.next();
		String storageKey = "blobs/" + blobId;
		UploadSession saved = this.sessions.inPartition(located.partitionKey(), context.sub(), () -> {
			UploadSession session = reloadSession(sessionId);
			if (UploadSession.STATUS_READY.equals(session.getStatus())
					|| UploadSession.STATUS_COMPLETED.equals(session.getStatus())) {
				return session;
			}
			requireSessionUsable(session);
			if (this.blobs.sumSizeByUploader(context.sub()) + staged.length > this.maxUserBytes) {
				throw AccessException.validation("Vượt hạn mức lưu trữ của người dùng");
			}
			if (this.blobs.sumSizeInPartition() + staged.length > this.maxWorkspaceBytes) {
				throw AccessException.validation("Vượt hạn mức lưu trữ của workspace");
			}
			this.storage.promote(stagingKey, storageKey);
			OffsetDateTime now = OffsetDateTime.now();
			FileBlob blob = new FileBlob();
			blob.setId(blobId);
			blob.setStorageKey(storageKey);
			blob.setContentType(image.contentType());
			blob.setSizeBytes(staged.length);
			blob.setChecksumSha256(checksum);
			blob.setWidth(image.width());
			blob.setHeight(image.height());
			blob.setStatus(FileBlob.STATUS_READY);
			blob.setUploadedBySub(context.sub());
			blob.setCreatedAt(now);
			blob.setUpdatedAt(now);
			this.blobs.save(blob);
			session.setBlobId(blobId);
			session.setStagingKey(null);
			session.setStatus(UploadSession.STATUS_READY);
			session.setUpdatedAt(now);
			writeAudit(context, FileAuditLog.ACTION_UPLOAD_FINALIZED, session.getTargetResourceId(),
					session.getTargetVersionId(), blobId, null);
			return this.sessionsRepo.save(session);
		});
		if (!blobId.equals(saved.getBlobId())) {
			this.storage.deleteStaged(stagingKey);
		}
		return sessionView(saved);
	}

	public LinkView attach(UUID sessionId, AttachRequest request) {
		TenantContext context = requireContext();
		LocatedSession located = locateSession(context, sessionId);
		UploadSession snapshot = located.session();
		requireUploader(context, snapshot);
		if (request != null && request.resourceId() != null
				&& !request.resourceId().equals(snapshot.getTargetResourceId())) {
			throw AccessException.validation("Đích gắn đã khoá trong phiên tải lên, không đổi đích được");
		}
		if (request != null && request.versionId() != null
				&& !request.versionId().equals(snapshot.getTargetVersionId())) {
			throw AccessException.validation("Đích gắn đã khoá trong phiên tải lên, không đổi đích được");
		}
		String position = (request != null) ? request.position() : null;
		requirePosition(position);
		if (UploadSession.STATUS_COMPLETED.equals(snapshot.getStatus()) && snapshot.getLinkId() != null) {
			return linkView(context, located.partitionKey(), loadLink(located.partitionKey(), context, snapshot.getLinkId()));
		}
		// Quyền ghi tại đích được kiểm lại đúng tại thời điểm gắn: phiên cũ không giúp người đã bị gỡ quyền
		// hoàn tất việc gắn tệp.
		requireAllowed(this.decisions.decide(context, snapshot.getTargetResourceId(), snapshot.getTargetVersionId(),
				AccessAction.EDIT_DRAFT));
		String blockId = (request != null) ? request.blockId() : null;
		FileLink saved = this.sessions.inPartition(located.partitionKey(), context.sub(), () -> {
			UploadSession session = reloadSession(sessionId);
			if (UploadSession.STATUS_COMPLETED.equals(session.getStatus()) && session.getLinkId() != null) {
				return this.links.findById(session.getLinkId()).orElseThrow(() -> AccessException
						.notFound("Không tìm thấy liên kết tệp của phiên tải lên"));
			}
			if (!UploadSession.STATUS_READY.equals(session.getStatus())) {
				throw AccessException.stateConflict("Phiên tải lên chưa sẵn sàng để gắn tệp");
			}
			requireSessionUsable(session);
			OffsetDateTime now = OffsetDateTime.now();
			FileLink link = new FileLink();
			link.setId(UuidV7.next());
			link.setResourceId(session.getTargetResourceId());
			link.setResourceVersionId(session.getTargetVersionId());
			link.setPosition(position);
			link.setBlockId(blockId);
			link.setBlobId(session.getBlobId());
			link.setDisplayName(session.getDisplayName());
			link.setSessionId(session.getId());
			link.setCreatedBySub(context.sub());
			link.setCreatedAt(now);
			link.setUpdatedAt(now);
			FileLink created = this.links.save(link);
			session.setLinkId(created.getId());
			session.setStatus(UploadSession.STATUS_COMPLETED);
			session.setUpdatedAt(now);
			this.sessionsRepo.save(session);
			writeAudit(context, FileAuditLog.ACTION_ATTACH, created.getResourceId(), created.getResourceVersionId(),
					created.getBlobId(), created.getId());
			return created;
		});
		return linkView(context, located.partitionKey(), saved);
	}

	public LinkView attachBlob(AttachBlobRequest request) {
		TenantContext context = requireContext();
		if (request == null || request.blobId() == null || request.resourceId() == null
				|| request.versionId() == null || request.displayName() == null
				|| request.displayName().isBlank()) {
			throw AccessException
					.validation("Thao tác gắn bằng định danh blob cần blobId, đích gắn và tên hiển thị");
		}
		requirePosition(request.position());
		Located located = this.decisions.locate(context, request.resourceId());
		if (located == null) {
			throw AccessException.notFound("Không tìm thấy tài nguyên");
		}
		requireAllowed(this.decisions.decide(context, request.resourceId(), request.versionId(),
				AccessAction.EDIT_DRAFT));
		LocatedBlob locatedBlob = locateBlob(context, request.blobId());
		if (locatedBlob == null) {
			throw AccessException.notFound("Không tìm thấy blob");
		}
		requireBlobUsable(context, locatedBlob);
		FileLink saved = this.sessions.inPartition(located.partitionKey(), context.sub(), () -> {
			OffsetDateTime now = OffsetDateTime.now();
			FileLink link = new FileLink();
			link.setId(UuidV7.next());
			link.setResourceId(request.resourceId());
			link.setResourceVersionId(request.versionId());
			link.setPosition(request.position());
			link.setBlockId(request.blockId());
			link.setBlobId(request.blobId());
			link.setDisplayName(request.displayName());
			link.setCreatedBySub(context.sub());
			link.setCreatedAt(now);
			link.setUpdatedAt(now);
			FileLink created = this.links.save(link);
			writeAudit(context, FileAuditLog.ACTION_ATTACH, created.getResourceId(), created.getResourceVersionId(),
					created.getBlobId(), created.getId());
			return created;
		});
		return linkView(context, located.partitionKey(), saved);
	}

	public FileContent readContent(UUID linkId) {
		TenantContext context = requireContext();
		LocatedLink located = locateLink(context, linkId);
		FileLink link = located.link();
		AccessAction action = link.answerArea() ? AccessAction.VIEW_ANSWER : AccessAction.VIEW_CONTENT;
		requireAllowed(this.decisions.decide(context, link.getResourceId(), link.getResourceVersionId(), action));
		FileBlob blob = this.sessions.inPartition(located.partitionKey(), context.sub(),
				() -> this.blobs.findById(link.getBlobId()).orElse(null));
		if (blob == null || !FileBlob.STATUS_READY.equals(blob.getStatus())) {
			throw AccessException.notFound("Không tìm thấy tệp");
		}
		this.sessions.inPartition(located.partitionKey(), context.sub(), () -> writeAudit(context,
				FileAuditLog.ACTION_READ, link.getResourceId(), link.getResourceVersionId(), blob.getId(),
				link.getId()));
		return new FileContent(this.storage.read(blob.getStorageKey()), blob.getContentType(),
				link.getDisplayName());
	}

	public List<LinkView> listLinks(UUID resourceId, UUID versionId) {
		TenantContext context = requireContext();
		Located located = this.decisions.locate(context, resourceId);
		if (located == null) {
			throw AccessException.notFound("Không tìm thấy tài nguyên");
		}
		requireAllowed(this.decisions.decide(context, resourceId, versionId, AccessAction.VIEW_CONTENT));
		boolean canAnswer = this.decisions.decide(context, resourceId, versionId, AccessAction.VIEW_ANSWER)
				.allowed();
		List<FileLink> rows = this.sessions.inPartition(located.partitionKey(), context.sub(),
				() -> this.links.findByResourceVersionIdOrderByCreatedAtAsc(versionId));
		return rows.stream().filter(link -> link.getResourceId().equals(resourceId))
				.filter(link -> canAnswer || !link.answerArea())
				.map(link -> linkView(context, located.partitionKey(), link)).toList();
	}

	public void quarantine(UUID blobId) {
		TenantContext context = requireContext();
		if (!context.platformAdmin()) {
			throw AccessException.forbidden("Chỉ quản trị nền tảng được cách ly blob");
		}
		LocatedBlob locatedBlob = locateBlob(context, blobId);
		if (locatedBlob == null) {
			throw AccessException.notFound("Không tìm thấy blob");
		}
		this.sessions.inPartition(locatedBlob.partitionKey(), context.sub(), () -> {
			FileBlob blob = this.blobs.findById(blobId)
					.orElseThrow(() -> AccessException.notFound("Không tìm thấy blob"));
			blob.setStatus(FileBlob.STATUS_QUARANTINED);
			blob.setUpdatedAt(OffsetDateTime.now());
			this.blobs.save(blob);
			writeAudit(context, FileAuditLog.ACTION_QUARANTINE, null, null, blobId, null);
		});
	}

	/**
	 * Đánh dấu các phiên chưa hoàn tất đã quá hạn trong một phân vùng thành hết hạn và xoá byte tạm còn sót của
	 * chúng. Việc quét mọi phân vùng không thực hiện được từ một job nền vì discriminator và RLS khoá mỗi phiên
	 * làm việc vào đúng một phân vùng; điểm vào này dùng cho đường vận hành theo phân vùng và cho kiểm chứng,
	 * còn byte tạm quá hạn trên đĩa được dọn định kỳ bởi {@link FileSessionCleanup}.
	 */
	public int expireStaleSessions(String partitionKey, OffsetDateTime now) {
		List<UploadSession> stale = this.sessions.inPartition(partitionKey, "system", () -> this.sessionsRepo
				.findByStatusInAndExpiresAtBefore(List.of(UploadSession.STATUS_OPEN, UploadSession.STATUS_READY),
						now));
		for (UploadSession session : stale) {
			this.storage.deleteStaged(session.getStagingKey());
		}
		return this.sessions.inPartition(partitionKey, "system", () -> {
			int count = 0;
			for (UploadSession session : this.sessionsRepo.findByStatusInAndExpiresAtBefore(
					List.of(UploadSession.STATUS_OPEN, UploadSession.STATUS_READY), now)) {
				session.setStatus(UploadSession.STATUS_EXPIRED);
				session.setUpdatedAt(now);
				this.sessionsRepo.save(session);
				count++;
			}
			return count;
		});
	}

	private record LocatedSession(UploadSession session, String partitionKey) {
	}

	private record LocatedLink(FileLink link, String partitionKey) {
	}

	private record LocatedBlob(FileBlob blob, String partitionKey) {
	}

	private LocatedSession locateSession(TenantContext context, UUID sessionId) {
		for (String partition : candidatePartitions(context)) {
			UploadSession session = this.sessions.inPartition(partition, context.sub(),
					() -> this.sessionsRepo.findById(sessionId).orElse(null));
			if (session != null) {
				return new LocatedSession(session, partition);
			}
		}
		throw AccessException.notFound("Không tìm thấy phiên tải lên");
	}

	private LocatedLink locateLink(TenantContext context, UUID linkId) {
		for (String partition : candidatePartitions(context)) {
			FileLink link = this.sessions.inPartition(partition, context.sub(),
					() -> this.links.findById(linkId).orElse(null));
			if (link != null) {
				return new LocatedLink(link, partition);
			}
		}
		// Hàng grant của người gọi nằm ở phân vùng bên nhận và mang khoá phân vùng nguồn của tài nguyên — cùng
		// cơ chế locate của lõi quyền: liên kết của một tài nguyên được grant nằm ở phân vùng nguồn đó, không
		// nằm ở các phân vùng của chính người gọi.
		java.util.LinkedHashSet<String> sourcePartitions = new java.util.LinkedHashSet<>();
		for (ResourceGrant grant : callerGrants(context)) {
			sourcePartitions.add(grant.getSourcePartitionKey());
		}
		for (String partition : sourcePartitions) {
			FileLink link = this.sessions.inPartition(partition, context.sub(),
					() -> this.links.findById(linkId).orElse(null));
			if (link != null) {
				return new LocatedLink(link, partition);
			}
		}
		throw AccessException.notFound("Không tìm thấy liên kết tệp");
	}

	private java.util.List<ResourceGrant> callerGrants(TenantContext context) {
		java.util.List<ResourceGrant> result = new java.util.ArrayList<>();
		result.addAll(this.sessions.inPartition("user:" + context.sub(), context.sub(),
				() -> this.grants.findByGranteeUserSub(context.sub())));
		if (context.organizationId() != null && !context.guest()) {
			result.addAll(this.sessions.inPartition(context.organizationId(), context.sub(),
					() -> this.grants.findByGranteeOrganizationId(context.organizationId())));
		}
		return result;
	}

	private LocatedBlob locateBlob(TenantContext context, UUID blobId) {
		for (String partition : candidatePartitions(context)) {
			FileBlob blob = this.sessions.inPartition(partition, context.sub(),
					() -> this.blobs.findById(blobId).orElse(null));
			if (blob != null) {
				return new LocatedBlob(blob, partition);
			}
		}
		return null;
	}

	// Gắn một blob vào đích mới đòi quyền sử dụng blob nguồn: người gọi là người đã tải blob đó lên, hoặc đọc
	// được nội dung qua ít nhất một liên kết hiện có của blob theo quyết định của lõi quyền. Chỉ gửi định danh
	// của blob thuộc về người khác thì bị từ chối.
	private void requireBlobUsable(TenantContext context, LocatedBlob locatedBlob) {
		FileBlob blob = locatedBlob.blob();
		if (!FileBlob.STATUS_READY.equals(blob.getStatus())) {
			throw AccessException.notFound("Không tìm thấy blob");
		}
		if (context.sub().equals(blob.getUploadedBySub())) {
			return;
		}
		List<FileLink> existing = this.sessions.inPartition(locatedBlob.partitionKey(), context.sub(),
				() -> this.links.findByBlobId(blob.getId()));
		for (FileLink link : existing) {
			AccessAction action = link.answerArea() ? AccessAction.VIEW_ANSWER : AccessAction.VIEW_CONTENT;
			if (this.decisions.decide(context, link.getResourceId(), link.getResourceVersionId(), action)
					.allowed()) {
				return;
			}
		}
		throw AccessException.forbidden("Người gọi không có quyền sử dụng blob nguồn này");
	}

	private FileLink loadLink(String partitionKey, TenantContext context, UUID linkId) {
		return this.sessions.inPartition(partitionKey, context.sub(),
				() -> this.links.findById(linkId)
						.orElseThrow(() -> AccessException.notFound("Không tìm thấy liên kết tệp")));
	}

	private UploadSession reloadSession(UUID sessionId) {
		return this.sessionsRepo.findById(sessionId)
				.orElseThrow(() -> AccessException.notFound("Không tìm thấy phiên tải lên"));
	}

	private void writeAudit(TenantContext context, String action, UUID resourceId, UUID versionId, UUID blobId,
			UUID linkId) {
		FileAuditLog entry = new FileAuditLog();
		entry.setId(UuidV7.next());
		entry.setActorSub(context.sub());
		entry.setAction(action);
		entry.setResourceId(resourceId);
		entry.setResourceVersionId(versionId);
		entry.setBlobId(blobId);
		entry.setLinkId(linkId);
		entry.setCreatedAt(OffsetDateTime.now());
		this.audit.save(entry);
	}

	private SessionView sessionView(UploadSession session) {
		String state = switch (session.getStatus()) {
			case UploadSession.STATUS_OPEN -> "uploading";
			case UploadSession.STATUS_READY, UploadSession.STATUS_COMPLETED -> "ready";
			default -> "rejected";
		};
		return new SessionView(session.getId(), state, session.getTargetResourceId(), session.getTargetVersionId(),
				session.getDisplayName(), session.getBlobId(), session.getLinkId(), session.getExpiresAt());
	}

	private LinkView linkView(TenantContext context, String partitionKey, FileLink link) {
		FileBlob blob = this.sessions.inPartition(partitionKey, context.sub(),
				() -> this.blobs.findById(link.getBlobId()).orElse(null));
		if (blob == null) {
			LocatedBlob locatedBlob = locateBlob(context, link.getBlobId());
			blob = (locatedBlob != null) ? locatedBlob.blob() : null;
		}
		String state = null;
		if (blob != null) {
			state = FileBlob.STATUS_READY.equals(blob.getStatus()) ? "ready" : "quarantined";
		}
		return new LinkView(link.getId(), link.getResourceId(), link.getResourceVersionId(), link.getPosition(),
				link.getBlockId(), link.getBlobId(), link.getDisplayName(),
				(blob != null) ? blob.getContentType() : null, (blob != null) ? blob.getSizeBytes() : 0, state,
				link.getCreatedAt());
	}

	private static void requireUploader(TenantContext context, UploadSession session) {
		if (!context.sub().equals(session.getUploaderSub())) {
			throw AccessException.notFound("Không tìm thấy phiên tải lên");
		}
	}

	private static void requireSessionUsable(UploadSession session) {
		if (UploadSession.STATUS_EXPIRED.equals(session.getStatus())
				|| session.getExpiresAt().isBefore(OffsetDateTime.now())) {
			throw AccessException.stateConflict("Phiên tải lên đã hết hạn");
		}
	}

	private static void requirePosition(String position) {
		if (position == null || !POSITIONS.contains(position)) {
			throw AccessException.validation("Vị trí gắn tệp không hợp lệ");
		}
	}

	private static void requireAllowed(Decision decision) {
		if (decision.allowed()) {
			return;
		}
		if (AccessDecisionService.REASON_NOT_FOUND.equals(decision.reasonCode())
				|| AccessDecisionService.REASON_VERSION_MISMATCH.equals(decision.reasonCode())) {
			throw AccessException.notFound("Không tìm thấy tài nguyên");
		}
		throw AccessException.forbidden("Quyết định quyền từ chối thao tác: " + decision.reasonCode());
	}

	private static List<String> candidatePartitions(TenantContext context) {
		LinkedHashSet<String> partitions = new LinkedHashSet<>();
		if (context.partitionKey() != null) {
			partitions.add(context.partitionKey());
		}
		partitions.add("user:" + context.sub());
		if (context.organizationId() != null && !context.guest()) {
			partitions.add(context.organizationId());
		}
		if (context.platformAdmin()) {
			partitions.add("system");
		}
		return List.copyOf(partitions);
	}

	private static TenantContext requireContext() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw AccessException.forbidden("Thiếu ngữ cảnh tenant đã xác minh");
		}
		return context;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
