package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Hợp đồng riêng của tệp bài làm trên nền pipeline tải lên dùng chung: tệp chỉ gắn được khi lượt làm còn mở,
 * mốc hợp lệ là thời gian server tại bước chốt gắn đối chiếu hạn của lượt làm, và chỉ tệp đã chốt xong trong
 * hạn mới tính là đã nộp. Sau khi lượt làm khoá, tệp là bất biến — gắn thêm, gỡ (đường thay tệp đi qua gỡ rồi
 * gắn lại) đều bị từ chối tại cùng một cổng kiểm trạng thái ở đây. Đọc lại đi theo đúng chủ thể: học viên chủ
 * bài làm và giáo viên được phân công dạy lớp đích.
 */
@Service
public class SubmissionFileService {

	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

	private final ExamAssignmentRepository assignments;

	private final ExamAttemptRepository attempts;

	private final SubmissionFileRepository files;

	private final TeachingChecks teaching;

	private final AssignmentPartitions partitions;

	private final SubmissionFileBlobs blobs;

	private final int maxFiles;

	private final long maxFileBytes;

	private final long maxTotalBytes;

	public SubmissionFileService(ExamAssignmentRepository assignments, ExamAttemptRepository attempts,
			SubmissionFileRepository files, TeachingChecks teaching, AssignmentPartitions partitions,
			SubmissionFileBlobs blobs, @Value("${app.submission-files.max-files:10}") int maxFiles,
			@Value("${app.submission-files.max-file-bytes:5242880}") long maxFileBytes,
			@Value("${app.submission-files.max-total-bytes:20971520}") long maxTotalBytes) {
		this.assignments = assignments;
		this.attempts = attempts;
		this.files = files;
		this.teaching = teaching;
		this.partitions = partitions;
		this.blobs = blobs;
		this.maxFiles = maxFiles;
		this.maxFileBytes = maxFileBytes;
		this.maxTotalBytes = maxTotalBytes;
	}

	// Hình chiếu một tệp bài làm: `status` là trạng thái xử lý theo hợp đồng tệp (ready mới là đã chốt) và
	// `submitted` chỉ bật đúng với các tệp ready — giao diện không được báo đã nộp cho tệp còn đang xử lý.
	public record FileView(UUID id, UUID attemptId, UUID blobId, String displayName, String contentType,
			Long sizeBytes, String status, boolean submitted, OffsetDateTime attachedAt) {
	}

	public record FileContent(byte[] bytes, String contentType, String displayName) {
	}

	public FileView attach(TenantContext context, UUID attemptId, UUID uploadSessionId) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = attempt(attemptId);
			ExamAssignment assignment = assignmentOf(attempt);
			requireStudentOwner(context, partition, attempt, assignment);
			requireOpenForAttach(attempt, assignment);
			if (!this.teaching.isEnrolled(context.sub(), partition, assignment.getClassId())) {
				throw AssignmentException.forbidden("Ghi danh vào lớp đã bị gỡ nên không gắn tệp được.");
			}
			SubmissionFile existing = this.files.findByAttemptIdAndUploadSessionId(attemptId, uploadSessionId)
					.orElse(null);
			if (existing != null) {
				// Gắn lại cùng một phiên là retry của cùng một thao tác chốt: trả nguyên hàng đã có, không
				// tạo liên kết thừa và không tính lại hạn mức.
				return view(existing, assignment);
			}
			SubmissionFileBlobs.SessionInfo session = this.blobs.findSession(uploadSessionId)
					.orElseThrow(() -> AssignmentException.validation(
							"Phiên tải lên không tồn tại hoặc nền tệp chưa sẵn sàng."));
			if (!context.sub().equals(session.uploadedBySub())) {
				throw AssignmentException.forbidden("Phiên tải lên thuộc về người dùng khác.");
			}
			// Định dạng và dung lượng chỉ kiểm được khi blob đã chốt: phiên còn đang xử lý chưa có hai dữ
			// kiện này, phần kiểm của nó nằm ở pipeline tải lên và ở lần đối chiếu khi tệp thành ready.
			if (session.contentType() != null && !ALLOWED_CONTENT_TYPES.contains(session.contentType())) {
				throw AssignmentException.validation("Tệp bài làm chỉ nhận định dạng PNG, JPEG hoặc WebP.");
			}
			Long size = session.sizeBytes();
			if (size != null && (size <= 0 || size > this.maxFileBytes)) {
				throw AssignmentException.validation("Tệp bài làm vượt dung lượng tối đa cho một tệp.");
			}
			List<SubmissionFile> current = this.files.findByAttemptIdOrderByCreatedAtAsc(attemptId);
			if (current.size() >= this.maxFiles) {
				throw AssignmentException.validation("Một lượt làm chỉ gắn tối đa " + this.maxFiles + " tệp.");
			}
			long total = current.stream()
					.mapToLong(file -> file.getSizeBytes() != null ? file.getSizeBytes() : 0L)
					.sum();
			if (size != null && total + size > this.maxTotalBytes) {
				throw AssignmentException.validation("Tổng dung lượng tệp của một lượt làm đã vượt hạn mức.");
			}
			OffsetDateTime now = now();
			SubmissionFile file = new SubmissionFile();
			file.setId(UuidV7.next());
			file.setAttemptId(attemptId);
			file.setUploadSessionId(uploadSessionId);
			file.setBlobId(session.blobId());
			file.setDisplayName(session.displayName() != null ? session.displayName() : "");
			file.setContentType(session.contentType());
			file.setSizeBytes(size);
			if (session.blobId() == null) {
				file.setStatus(SubmissionFile.PROCESSING);
			}
			else if ("QUARANTINED".equals(session.blobStatus())) {
				file.setStatus(SubmissionFile.QUARANTINED);
			}
			else if (session.blobCreatedAt() != null && session.blobCreatedAt().isAfter(assignment.getDueAt())) {
				// Phiên hoàn tất chốt sau hạn của lượt làm: tệp không được gắn và không tính là đã nộp, dù
				// thao tác gắn diễn ra khi lượt còn đang làm trên dữ kiện.
				throw AssignmentException.stateConflict("Tệp hoàn tất sau hạn nộp nên không được gắn.");
			}
			else {
				file.setStatus(SubmissionFile.READY);
				file.setAttachedAt(now);
			}
			file.setCreatedAt(now);
			file.setUpdatedAt(now);
			this.files.saveAndFlush(file);
			return view(file, assignment);
		});
	}

	public List<FileView> list(TenantContext context, UUID attemptId) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = attempt(attemptId);
			ExamAssignment assignment = assignmentOf(attempt);
			requireReader(context, partition, attempt, assignment);
			return this.files.findByAttemptIdOrderByCreatedAtAsc(attemptId).stream()
					.map(file -> view(file, assignment))
					.toList();
		});
	}

	public FileContent content(TenantContext context, UUID attemptId, UUID fileId) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = attempt(attemptId);
			ExamAssignment assignment = assignmentOf(attempt);
			requireReader(context, partition, attempt, assignment);
			SubmissionFile file = this.files.findById(fileId)
					.filter(candidate -> candidate.getAttemptId().equals(attemptId))
					.orElseThrow(() -> AssignmentException.notFound("Tệp bài làm không tồn tại."));
			FileView view = view(file, assignment);
			if (!SubmissionFile.READY.equals(view.status()) || file.getBlobId() == null) {
				throw AssignmentException.notFound("Tệp bài làm chưa sẵn sàng để đọc.");
			}
			byte[] bytes = this.blobs.readBlob(file.getBlobId())
					.orElseThrow(() -> new AssignmentException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_UNAVAILABLE",
							"Chưa đọc được dữ kiện tệp từ nền tệp."));
			return new FileContent(bytes, file.getContentType(), file.getDisplayName());
		});
	}

	public List<FileView> detach(TenantContext context, UUID attemptId, UUID fileId) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = attempt(attemptId);
			ExamAssignment assignment = assignmentOf(attempt);
			requireStudentOwner(context, partition, attempt, assignment);
			requireOpenForAttach(attempt, assignment);
			SubmissionFile file = this.files.findById(fileId)
					.filter(candidate -> candidate.getAttemptId().equals(attemptId))
					.orElseThrow(() -> AssignmentException.notFound("Tệp bài làm không tồn tại."));
			this.files.delete(file);
			this.files.flush();
			return this.files.findByAttemptIdOrderByCreatedAtAsc(attemptId).stream()
					.map(candidate -> view(candidate, assignment))
					.toList();
		});
	}

	// Trạng thái hiệu lực của một hàng khi đọc: hàng đang xử lý được đối chiếu với phiên tải lên hiện hành và
	// kiểm lại đúng các kiểm của bước gắn — vì lúc gắn dữ kiện phiên còn rỗng nên các kiểm này chưa chạy được.
	// Chỉ trạng thái ready mới ghi lại xuống hàng đã lưu, để đường đọc nội dung — vốn kiểm blobId trên hàng
	// đã lưu — đọc được đúng blob; trạng thái bị từ chối và cách ly chỉ suy ra khi đọc, vì ràng buộc CHECK
	// của V96 không cho phép ghi trạng thái bị từ chối và định dạng của một blob bị cách ly có thể nằm ngoài
	// danh sách định dạng V96 cho phép.
	private FileView view(SubmissionFile file, ExamAssignment assignment) {
		String status = file.getStatus();
		UUID blobId = file.getBlobId();
		String contentType = file.getContentType();
		Long sizeBytes = file.getSizeBytes();
		OffsetDateTime attachedAt = file.getAttachedAt();
		if (SubmissionFile.PROCESSING.equals(status)) {
			SubmissionFileBlobs.SessionInfo session = this.blobs.findSession(file.getUploadSessionId())
					.orElse(null);
			if (session != null && session.blobId() != null) {
				blobId = session.blobId();
				contentType = session.contentType();
				sizeBytes = session.sizeBytes();
				if ("QUARANTINED".equals(session.blobStatus())) {
					status = SubmissionFile.QUARANTINED;
				}
				else if (session.blobCreatedAt() != null
						&& session.blobCreatedAt().isAfter(assignment.getDueAt())) {
					status = SubmissionFile.REJECTED;
				}
				else if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType) || sizeBytes == null
						|| sizeBytes <= 0 || sizeBytes > this.maxFileBytes) {
					status = SubmissionFile.REJECTED;
				}
				else if (readyTotalBytes(file) + sizeBytes > this.maxTotalBytes) {
					// Chốt muộn làm tổng vượt hạn: các tệp ready khác đã chiếm hết hạn mức từ khi hàng này còn
					// đang xử lý, nên tệp này bị từ chối thay vì thành ready và phá hạn mức tổng.
					status = SubmissionFile.REJECTED;
				}
				else {
					status = SubmissionFile.READY;
					attachedAt = session.blobCreatedAt();
				}
				if (SubmissionFile.READY.equals(status)) {
					file.setBlobId(blobId);
					file.setContentType(contentType);
					file.setSizeBytes(sizeBytes);
					file.setStatus(status);
					file.setAttachedAt(attachedAt);
					file.setUpdatedAt(now());
					this.files.saveAndFlush(file);
				}
			}
		}
		return new FileView(file.getId(), file.getAttemptId(), blobId, file.getDisplayName(), contentType,
				sizeBytes, status, SubmissionFile.READY.equals(status), attachedAt);
	}

	// Tổng dung lượng các tệp đã ready của cùng lượt làm, trừ chính hàng đang đối chiếu: mốc tính hạn mức tổng
	// cho một tệp chốt muộn, vì các tệp ready khác có thể đã chốt xong sau khi hàng này được gắn lúc còn xử lý.
	private long readyTotalBytes(SubmissionFile file) {
		return this.files.findByAttemptIdOrderByCreatedAtAsc(file.getAttemptId())
				.stream()
				.filter(candidate -> !candidate.getId().equals(file.getId())
						&& SubmissionFile.READY.equals(candidate.getStatus()))
				.mapToLong(candidate -> candidate.getSizeBytes() != null ? candidate.getSizeBytes() : 0L)
				.sum();
	}

	// Cổng khoá dùng chung cho gắn và gỡ: lượt còn đang làm nhưng đã qua hạn thì thao tác chốt tại thời điểm
	// này là chốt muộn — bị từ chối tại đây thay vì chờ chốt lười của đường đọc lượt làm, vì mốc hợp lệ là
	// chính thời gian server tại bước chốt này.
	private void requireOpenForAttach(ExamAttempt attempt, ExamAssignment assignment) {
		if (!ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
			throw AssignmentException.stateConflict("Bài đã nộp và khoá nên tệp không thay đổi được.");
		}
		if (now().isAfter(assignment.getDueAt())) {
			throw AssignmentException.stateConflict("Đã quá hạn nộp; tệp không được gắn vào bài.");
		}
	}

	private void requireReader(TenantContext context, String partition, ExamAttempt attempt,
			ExamAssignment assignment) {
		if (context.sub().equals(attempt.getStudentSub())) {
			return;
		}
		if (!context.guest() && this.teaching.isAssignedToTeach(context.sub(), partition,
				assignment.getClassId())) {
			return;
		}
		requireStudentOwner(context, partition, attempt, assignment);
	}

	// Cùng quy ước phân biệt của đường đọc lượt làm: bạn học cùng lớp thì coi như không tồn tại (404), các
	// vai còn lại bị từ chối quyền (403).
	private void requireStudentOwner(TenantContext context, String partition, ExamAttempt attempt,
			ExamAssignment assignment) {
		if (context.sub().equals(attempt.getStudentSub())) {
			return;
		}
		if (this.teaching.isEnrolled(context.sub(), partition, assignment.getClassId())) {
			throw AssignmentException.notFound("Lượt làm không tồn tại.");
		}
		throw AssignmentException.forbidden("Lượt làm thuộc về học viên khác.");
	}

	private ExamAttempt attempt(UUID attemptId) {
		return this.attempts.findById(attemptId)
				.orElseThrow(() -> AssignmentException.notFound("Lượt làm không tồn tại."));
	}

	private ExamAssignment assignmentOf(ExamAttempt attempt) {
		return this.assignments.findById(attempt.getAssignmentId())
				.orElseThrow(() -> AssignmentException.notFound("Lần giao không tồn tại."));
	}

	private String readPartition(TenantContext context) {
		if (context == null || context.organizationAlias() == null) {
			throw AssignmentException.notFound("Không tìm thấy dữ kiện trong ngữ cảnh trang hiện tại.");
		}
		return this.partitions.requirePagePartition(context);
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

}
