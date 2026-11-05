package com.giaokhoa.server.attachment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.giaokhoa.server.question.Question;
import com.giaokhoa.server.question.QuestionPublishGate;
import com.giaokhoa.server.question.QuestionRepository;
import com.giaokhoa.server.question.QuestionRights;
import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.QuestionVersionRepository;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

// Luồng tệp đính kèm theo đúng năm bước của hợp đồng: mở phiên khoá đích và giới hạn → nhận byte vào vùng tạm
// → kiểm tra nội dung thật và xử lý → chốt thành blob bất biến từ đúng byte đã kiểm tra → gắn vào bản nháp
// sau khi kiểm lại quyền tại thời điểm gắn. Quyền trên tệp quyết theo ngữ cảnh sử dụng (tài nguyên, phiên
// bản, vị trí) qua điểm nối quyền của feat câu hỏi, không quyết theo người đã tải tệp lên.
@Service
public class AttachmentService implements QuestionPublishGate {

	public static final Set<String> POSITIONS = Set.of("content", "choice", "answer", "solution", "document");

	private static final Set<String> ANSWER_POSITIONS = Set.of("answer", "solution");

	public record OpenSessionRequest(String position, String blockId, String displayName) {
	}

	public record CompleteRequest(UUID versionId, String position, String blockId) {
	}

	public record SessionView(UUID sessionId, UUID questionId, UUID versionId, String position, String blockId,
			String displayName, String status, String contentType, Long sizeBytes, Integer width, Integer height,
			String checksumSha256, UUID blobId, UUID linkId, OffsetDateTime expiresAt) {
	}

	public record LinkView(UUID linkId, UUID questionId, UUID versionId, String position, String blockId,
			UUID blobId, String displayName, String contentType, long sizeBytes, String checksumSha256) {
	}

	public record Content(byte[] bytes, String contentType, String displayName) {
	}

	private final AttachmentStore store;

	private final QuestionRepository questions;

	private final QuestionVersionRepository versions;

	private final QuestionRights rights;

	private final long maxBytes;

	private final long maxPixels;

	private final long sessionTtlHours;

	public AttachmentService(AttachmentStore store, QuestionRepository questions, QuestionVersionRepository versions,
			QuestionRights rights, @Value("${app.attachments.max-bytes:5242880}") long maxBytes,
			@Value("${app.attachments.max-pixels:20000000}") long maxPixels,
			@Value("${app.attachments.session-ttl-hours:24}") long sessionTtlHours) {
		this.store = store;
		this.questions = questions;
		this.versions = versions;
		this.rights = rights;
		this.maxBytes = maxBytes;
		this.maxPixels = maxPixels;
		this.sessionTtlHours = sessionTtlHours;
	}

	public SessionView openSession(UUID questionId, UUID versionId, OpenSessionRequest request) {
		TenantContext context = requireContext();
		Question question = requireQuestion(questionId);
		QuestionVersion version = requireVersion(question, versionId);
		requireDraftEditable(context, question, version);
		if (request == null || request.position() == null || !POSITIONS.contains(request.position())) {
			throw AttachmentException
					.validation("Vị trí gắn tệp không hợp lệ: chỉ gồm content, choice, answer, solution, document.");
		}
		String displayName = request.displayName() == null || request.displayName().isBlank() ? "tep-dinh-kem"
				: request.displayName().trim();
		AttachmentStore.Session session = new AttachmentStore.Session(UuidV7.next(), context.sub(), questionId,
				versionId, request.position(), request.blockId(), displayName, AttachmentStore.STATUS_OPEN, null,
				null, null, null, null, null, null, OffsetDateTime.now().plusHours(this.sessionTtlHours));
		return sessionView(this.store.saveSession(session));
	}

	public SessionView uploadContent(UUID questionId, UUID versionId, UUID sessionId, MultipartFile file) {
		TenantContext context = requireContext();
		AttachmentStore.Session session = requireOwnSession(context, sessionId);
		requireSessionContext(session, questionId, versionId);
		requireLiveSession(session);
		if (!AttachmentStore.STATUS_OPEN.equals(session.status())
				&& !AttachmentStore.STATUS_READY.equals(session.status())) {
			throw AttachmentException.conflict("STATE_CONFLICT", "Phiên tải lên đã chốt, không nhận thêm byte.");
		}
		byte[] raw;
		try {
			raw = file == null ? null : file.getBytes();
		}
		catch (Exception ex) {
			throw AttachmentException.fileRejected("Không đọc được byte của tệp tải lên.");
		}
		ImageProcessing.Processed processed = ImageProcessing.process(raw, this.maxBytes, this.maxPixels);
		// Vùng tạm chỉ giữ đúng byte đã qua kiểm tra kèm checksum của chính byte đó: bước chốt đối chiếu lại
		// checksum trước khi tạo blob nên byte bị thay ở vùng tạm sau bước kiểm tra không lọt vào blob chuẩn.
		this.store.stage(sessionId, processed.bytes());
		AttachmentStore.Session updated = session.withProcessing(AttachmentStore.STATUS_READY,
				processed.contentType(), processed.bytes().length, processed.width(), processed.height(),
				processed.checksumSha256());
		return sessionView(this.store.saveSession(updated));
	}

	public Content previewContent(UUID questionId, UUID versionId, UUID sessionId) {
		TenantContext context = requireContext();
		AttachmentStore.Session session = requireOwnSession(context, sessionId);
		requireSessionContext(session, questionId, versionId);
		if (AttachmentStore.STATUS_COMPLETED.equals(session.status()) && session.blobId() != null) {
			byte[] bytes = this.store.readBlob(session.blobId()).orElseThrow(AttachmentException::notFound);
			return new Content(bytes, session.contentType(), session.displayName());
		}
		if (!AttachmentStore.STATUS_READY.equals(session.status())) {
			throw AttachmentException.conflict("STATE_CONFLICT", "Phiên chưa có ảnh đã xử lý để xem trước.");
		}
		requireLiveSession(session);
		byte[] bytes = this.store.staged(sessionId).orElseThrow(AttachmentException::notFound);
		return new Content(bytes, session.contentType(), session.displayName());
	}

	public LinkView complete(UUID questionId, UUID versionId, UUID sessionId, CompleteRequest request) {
		TenantContext context = requireContext();
		AttachmentStore.Session session = requireOwnSession(context, sessionId);
		requireSessionContext(session, questionId, versionId);
		// Retry hoàn tất chỉ hợp lệ trên đúng đích đã khoá trong phiên: đích khác trong request bị từ chối
		// trước mọi xử lý, kể cả khi phiên đã chốt xong ở một lượt retry trước đó.
		if (request != null && ((request.versionId() != null && !request.versionId().equals(session.versionId()))
				|| (request.position() != null && !request.position().equals(session.position()))
				|| (request.blockId() != null && !request.blockId().equals(session.blockId())))) {
			throw AttachmentException.conflict("STATE_CONFLICT",
					"Đích gắn trong yêu cầu hoàn tất khác với đích đã khoá trong phiên tải lên.");
		}
		if (AttachmentStore.STATUS_COMPLETED.equals(session.status())) {
			return linkView(this.store.findLink(session.linkId()).orElseThrow(AttachmentException::notFound));
		}
		requireLiveSession(session);
		if (!AttachmentStore.STATUS_READY.equals(session.status())) {
			throw AttachmentException.conflict("STATE_CONFLICT", "Phiên tải lên chưa sẵn sàng để gắn.");
		}
		Question question = requireQuestion(session.questionId());
		QuestionVersion version = requireVersion(question, session.versionId());
		requireDraftEditable(context, question, version);
		byte[] staged = this.store.staged(sessionId).orElseThrow(AttachmentException::notFound);
		if (!ImageProcessing.sha256(staged).equals(session.stagedChecksum())) {
			throw AttachmentException.conflict("STATE_CONFLICT",
					"Byte ở vùng tạm đã đổi sau bước kiểm tra; phiên bị huỷ, hãy tải lên lại.");
		}
		AttachmentStore.Blob blob = this.store.saveBlob(new AttachmentStore.Blob(UuidV7.next(),
				session.contentType(), staged.length, session.stagedChecksum(), session.width(), session.height(),
				AttachmentStore.BLOB_READY), staged);
		AttachmentStore.Link link = this.store.saveLink(new AttachmentStore.Link(UuidV7.next(), session.questionId(),
				session.versionId(), session.position(), session.blockId(), blob.id(), session.displayName(),
				session.id()));
		this.store.saveSession(session.completed(blob.id(), link.id()));
		this.store.dropStaged(sessionId);
		return linkView(link);
	}

	public List<LinkView> listLinks(UUID questionId, UUID versionId) {
		TenantContext context = requireContext();
		Question question = requireQuestion(questionId);
		QuestionVersion version = requireVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.VIEW_CONTENT)) {
			throw AttachmentException.forbidden();
		}
		boolean includeAnswers = this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER);
		return this.store.findLinksForVersion(versionId).stream()
				.filter((link) -> includeAnswers || !ANSWER_POSITIONS.contains(link.position()))
				.map(this::linkView)
				.toList();
	}

	public Content readContent(UUID questionId, UUID versionId, UUID linkId) {
		TenantContext context = requireContext();
		Question question = requireQuestion(questionId);
		QuestionVersion version = requireVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.VIEW_CONTENT)) {
			throw AttachmentException.forbidden();
		}
		AttachmentStore.Link link = this.store.findLink(linkId)
				.filter((candidate) -> candidate.versionId().equals(versionId)
						&& candidate.questionId().equals(questionId))
				.orElseThrow(AttachmentException::notFound);
		// Tệp đáp án tách quyền tới từng tệp: người xem được nội dung nhưng không có view_answer thì đáp ứng
		// như tệp không tồn tại, trên mọi đường đọc kể cả khi đã biết định danh liên kết.
		if (ANSWER_POSITIONS.contains(link.position())
				&& !this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER)) {
			throw AttachmentException.notFound();
		}
		AttachmentStore.Blob blob = this.store.findBlob(link.blobId()).orElseThrow(AttachmentException::notFound);
		if (AttachmentStore.BLOB_QUARANTINED.equals(blob.status())) {
			throw AttachmentException.conflict("STATE_CONFLICT", "Tệp đã bị cách ly và không còn được phục vụ.");
		}
		byte[] bytes = this.store.readBlob(blob.id()).orElseThrow(AttachmentException::notFound);
		return new Content(bytes, blob.contentType(), link.displayName());
	}

	// Điểm nối cho thao tác sao chép câu hỏi: tệp đính kèm của phiên bản nguồn được mang sang phiên bản của
	// bản sao qua liên kết mới thuộc chính bản sao — blob vật lý dùng chung nhưng quyền và vòng đời tính
	// theo liên kết của từng bản. Tệp ở vị trí đáp án chỉ đi theo khi người sao chép có view_answer trên
	// phiên bản nguồn, khớp quy tắc đáp án của chính thao tác sao chép ở dịch vụ câu hỏi.
	public void carryLinks(UUID sourceVersionId, UUID targetQuestionId, UUID targetVersionId,
			boolean includeAnswers) {
		// Blob của tệp nguồn ở phân vùng của chủ phiên bản nguồn; đích thuộc chủ khác thì blob được dời
		// nhà sang phân vùng của chủ đích ngay khi mang liên kết, để bản sao đọc và phát hành được tệp
		// của chính nó và vòng đời tệp của hai bản độc lập. Cùng chủ thì hai phân vùng trùng nhau và
		// blob được dùng chung nguyên vẹn.
		String sourcePartition = this.versions.findById(sourceVersionId)
				.map((version) -> "user:" + version.getOwnerSub()).orElse(null);
		String targetPartition = this.questions.findById(targetQuestionId)
				.map((question) -> "user:" + question.getOwnerSub()).orElse(sourcePartition);
		for (AttachmentStore.Link link : this.store.findLinksForVersion(sourceVersionId)) {
			if (!includeAnswers && ANSWER_POSITIONS.contains(link.position())) {
				continue;
			}
			UUID blobId = this.store.rehomeBlob(link.blobId(), sourcePartition, targetPartition);
			this.store.saveLink(new AttachmentStore.Link(UuidV7.next(), targetQuestionId, targetVersionId,
					link.position(), link.blockId(), blobId, link.displayName(), null));
		}
	}

	// Cổng phát hành của feat câu hỏi: phiên bản còn phiên tải lên chưa chốt, hoặc còn liên kết trỏ tới blob
	// chưa sẵn sàng hay đã mất byte, thì chưa được phát hành hay giao bài. Phiên chưa chốt nhưng đã quá hạn
	// thì đã chết theo đúng thời hạn của nó: cổng đánh dấu hết hạn và dọn byte tạm ngay khi bắt gặp, thay vì
	// để một phiên bị bỏ dở chặn phát hành vô thời hạn dù không còn ai chạm được vào nó nữa.
	@Override
	public void assertPublishable(UUID versionId) {
		OffsetDateTime now = OffsetDateTime.now();
		boolean pending = false;
		for (AttachmentStore.Session session : this.store.findSessionsForVersion(versionId)) {
			if (!AttachmentStore.STATUS_OPEN.equals(session.status())
					&& !AttachmentStore.STATUS_READY.equals(session.status())) {
				continue;
			}
			if (session.expiresAt().isBefore(now)) {
				this.store.saveSession(session.withStatus(AttachmentStore.STATUS_EXPIRED));
				this.store.dropStaged(session.id());
			}
			else {
				pending = true;
			}
		}
		if (pending) {
			throw AttachmentException.conflict("ATTACHMENT_NOT_READY",
					"Phiên bản còn tệp đính kèm chưa hoàn tất tải lên nên chưa phát hành được.");
		}
		for (AttachmentStore.Link link : this.store.findLinksForVersion(versionId)) {
			AttachmentStore.Blob blob = this.store.findBlob(link.blobId()).orElse(null);
			if (blob == null || !AttachmentStore.BLOB_READY.equals(blob.status())
					|| this.store.readBlob(blob.id()).isEmpty()) {
				throw AttachmentException.conflict("ATTACHMENT_NOT_READY",
						"Phiên bản có tệp đính kèm chưa sẵn sàng hoặc đã mất dữ kiện nên chưa phát hành được.");
			}
		}
	}

	// Định danh câu và phiên bản trên đường dẫn phải đúng đích đã khoá trong phiên: một phiên chỉ dùng được
	// trong đúng ngữ cảnh của nó, sai ngữ cảnh thì đáp ứng như phiên không tồn tại.
	private void requireSessionContext(AttachmentStore.Session session, UUID questionId, UUID versionId) {
		if (!session.questionId().equals(questionId) || !session.versionId().equals(versionId)) {
			throw AttachmentException.notFound();
		}
	}

	private AttachmentStore.Session requireOwnSession(TenantContext context, UUID sessionId) {
		AttachmentStore.Session session = this.store.findSession(sessionId)
				.orElseThrow(AttachmentException::notFound);
		if (!session.uploaderSub().equals(context.sub())) {
			throw AttachmentException.notFound();
		}
		return session;
	}

	private void requireLiveSession(AttachmentStore.Session session) {
		if (AttachmentStore.STATUS_EXPIRED.equals(session.status())
				|| session.expiresAt().isBefore(OffsetDateTime.now())) {
			this.store.saveSession(session.withStatus(AttachmentStore.STATUS_EXPIRED));
			this.store.dropStaged(session.id());
			throw AttachmentException.conflict("STATE_CONFLICT", "Phiên tải lên đã hết hạn.");
		}
	}

	private void requireDraftEditable(TenantContext context, Question question, QuestionVersion version) {
		if (!this.rights.allowed(context, question, version, QuestionRights.EDIT_DRAFT)) {
			throw AttachmentException.forbidden();
		}
		if (version.locked()) {
			throw AttachmentException.conflict("VERSION_LOCKED",
					"Phiên bản đã khoá, không gắn thêm tệp được; hãy tạo phiên bản nháp mới.");
		}
	}

	private Question requireQuestion(UUID questionId) {
		return this.questions.findById(questionId).orElseThrow(AttachmentException::notFound);
	}

	private QuestionVersion requireVersion(Question question, UUID versionId) {
		return this.versions.findByIdAndQuestionId(versionId, question.getId())
				.orElseThrow(AttachmentException::notFound);
	}

	private SessionView sessionView(AttachmentStore.Session session) {
		String status = switch (session.status()) {
			case AttachmentStore.STATUS_OPEN -> "uploading";
			case AttachmentStore.STATUS_READY, AttachmentStore.STATUS_COMPLETED -> "ready";
			default -> "rejected";
		};
		return new SessionView(session.id(), session.questionId(), session.versionId(), session.position(),
				session.blockId(), session.displayName(), status, session.contentType(), session.sizeBytes(),
				session.width(), session.height(), session.stagedChecksum(), session.blobId(), session.linkId(),
				session.expiresAt());
	}

	private LinkView linkView(AttachmentStore.Link link) {
		AttachmentStore.Blob blob = this.store.findBlob(link.blobId()).orElseThrow(AttachmentException::notFound);
		return new LinkView(link.id(), link.questionId(), link.versionId(), link.position(), link.blockId(),
				link.blobId(), link.displayName(), blob.contentType(), blob.sizeBytes(), blob.checksumSha256());
	}

	private TenantContext requireContext() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw AttachmentException.forbidden();
		}
		return context;
	}

}
