package com.giaokhoa.server.math;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.giaokhoa.server.access.PartitionSessions;
import com.giaokhoa.server.files.BlobStorage;
import com.giaokhoa.server.files.FileBlob;
import com.giaokhoa.server.files.FileBlobRepository;

// Tầng ghi dữ kiện của lát LaTeX, tách khỏi dịch vụ điều phối để bước render (gọi worker ngoài) không nằm
// trong bất kỳ giao dịch nào: mỗi phương thức ở đây là một giao dịch ngắn quanh một bước ghi, và việc gắn kết
// quả render luôn kiểm lại revision tại chính giao dịch gắn.
@Component
class MathFormulaStore {

	private final FormulaBlockRepository blocks;

	private final FormulaRenderRepository renders;

	private final BlobStorage storage;

	private final FileBlobRepository blobs;

	private final PartitionSessions sessions;

	MathFormulaStore(FormulaBlockRepository blocks, FormulaRenderRepository renders, BlobStorage storage,
			FileBlobRepository blobs, PartitionSessions sessions) {
		this.blocks = blocks;
		this.renders = renders;
		this.storage = storage;
		this.blobs = blobs;
		this.sessions = sessions;
	}

	@Transactional(readOnly = true)
	List<FormulaBlock> blocksOf(UUID versionId) {
		return this.blocks.findByQuestionVersionIdOrderByPositionAsc(versionId);
	}

	@Transactional(readOnly = true)
	Optional<FormulaBlock> findBlock(UUID versionId, String blockId) {
		return this.blocks.findByQuestionVersionIdAndBlockId(versionId, blockId);
	}

	@Transactional(readOnly = true)
	long countBlocks(UUID versionId) {
		return this.blocks.countByQuestionVersionId(versionId);
	}

	// Bản ghi render hiện hành của một khối: khối có-nguồn khoá theo revision hiện tại của chính khối; khối
	// chỉ-có-ảnh không có revision nguồn nên bản ghi sẵn sàng mới nhất là bản ghi hiện hành của nó.
	@Transactional(readOnly = true)
	Optional<FormulaRender> currentRender(FormulaBlock block) {
		if (block.sourceBacked()) {
			return this.renders.findByFormulaBlockIdAndSourceRevision(block.getId(), block.getRevision());
		}
		return this.renders.findByFormulaBlockIdOrderBySourceRevisionDesc(block.getId())
			.stream()
			.filter(FormulaRender::ready)
			.findFirst();
	}

	record SavedSource(FormulaBlock block, FormulaRender render) {
	}

	@Transactional
	SavedSource createSourceBlock(UUID versionId, String ownerSub, String section, String blockId, int position,
			String source, String description, boolean display) {
		FormulaBlock block = new FormulaBlock();
		block.setId(UuidV7.generate());
		block.setQuestionVersionId(versionId);
		block.setOwnerSub(ownerSub);
		block.setSection(section);
		block.setBlockId(blockId);
		block.setPosition(position);
		block.setForm(FormulaBlock.FORM_SOURCE_BACKED);
		block.setDisplay(display);
		block.setSystemOrigin(false);
		block.setSource(source);
		block.setDescription(description);
		OffsetDateTime now = OffsetDateTime.now();
		block.setCreatedAt(now);
		block.setUpdatedAt(now);
		FormulaBlock saved = this.blocks.saveAndFlush(block);
		return new SavedSource(saved, pendingRender(saved));
	}

	// Sao chép toàn bộ khối công thức của một phiên bản sang một phiên bản khác. Khi đích là nháp mới của
	// cùng một tài nguyên (keepSource) nguồn đi theo nguyên vẹn để chủ sửa tiếp; khi đích là bản sao câu
	// hỏi thì mọi khối thành chỉ-có-ảnh và cột nguồn để trống — sao chép không chuyển nguồn trong mọi
	// trường hợp, kể cả khối tự soạn của chủ nguồn. Bản ghi render hiện hành được sao thành hàng mới trỏ
	// cùng blob ảnh: ảnh dùng được ngay trên phiên bản đích, còn vòng đời của hai bản ghi tính riêng;
	// trạng thái render chưa sẵn sàng cũng được sao nguyên trạng để cổng phát hành của phiên bản đích
	// chặn đúng như ở nguồn.
	@Transactional
	void copyBlocksTo(UUID sourceVersionId, UUID targetVersionId, String targetOwnerSub, boolean keepSource,
			String actorSub) {
		OffsetDateTime now = OffsetDateTime.now();
		for (FormulaBlock source : blocksOf(sourceVersionId)) {
			FormulaBlock block = new FormulaBlock();
			block.setId(UuidV7.generate());
			block.setQuestionVersionId(targetVersionId);
			block.setOwnerSub(targetOwnerSub);
			block.setSection(source.getSection());
			block.setBlockId(source.getBlockId());
			block.setPosition(source.getPosition());
			block.setDisplay(source.isDisplay());
			block.setSystemOrigin(source.isSystemOrigin());
			block.setDescription(source.getDescription());
			boolean keep = keepSource && source.sourceBacked();
			block.setForm(keep ? FormulaBlock.FORM_SOURCE_BACKED : FormulaBlock.FORM_RENDER_ONLY);
			block.setSource(keep ? source.getSource() : null);
			block.setCreatedAt(now);
			block.setUpdatedAt(now);
			FormulaBlock saved = this.blocks.saveAndFlush(block);
			currentRender(source).ifPresent((render) -> {
				FormulaRender copied = new FormulaRender();
				copied.setId(UuidV7.generate());
				copied.setFormulaBlockId(saved.getId());
				// Revision của khối sao là khoá lạc quan do Hibernate khởi tạo lại từ đầu, nên bản ghi render
				// của bản sao khoá theo đúng revision đó của khối sao — ảnh vẫn là ảnh của đúng nội dung khối
				// tại thời điểm sao chép, chỉ số revision là của vòng đời mới.
				copied.setSourceRevision(saved.getRevision());
				copied.setSourceHash(render.getSourceHash());
				copied.setStatus(render.getStatus());
				// Ảnh render nằm ở phân vùng của chủ khối nguồn (đường đọc ảnh định vị blob theo chủ
				// khối): khối sao thuộc chủ khác thì ảnh được dời nhà sang phân vùng của chủ mới với
				// định danh blob mới, cùng byte; cùng chủ thì dùng chung blob như cũ.
				UUID imageBlobId = render.getImageBlobId();
				if (imageBlobId != null && !source.getOwnerSub().equals(targetOwnerSub)) {
					imageBlobId = rehomeImage(imageBlobId, source.getOwnerSub(), targetOwnerSub, actorSub);
				}
				copied.setImageBlobId(imageBlobId);
				copied.setImageWidth(render.getImageWidth());
				copied.setImageHeight(render.getImageHeight());
				copied.setBaselineOffset(render.getBaselineOffset());
				copied.setRendererVersion(render.getRendererVersion());
				copied.setRenderConfig(render.getRenderConfig());
				copied.setErrorCode(render.getErrorCode());
				copied.setCreatedAt(now);
				copied.setUpdatedAt(now);
				this.renders.save(copied);
			});
		}
	}

	@Transactional
	SavedSource updateSource(UUID blockPk, String source, String description, boolean display) {
		FormulaBlock block = this.blocks.findById(blockPk).orElseThrow();
		block.setSource(source);
		block.setDescription(description);
		block.setDisplay(display);
		block.setUpdatedAt(OffsetDateTime.now());
		FormulaBlock saved = this.blocks.saveAndFlush(block);
		return new SavedSource(saved, pendingRender(saved));
	}

	// Tự soạn nguồn mới cho khối chỉ-có-ảnh: khối chuyển thành có-nguồn của chủ sở hữu hiện hành. Nguồn mới là
	// của chính người soạn nên cờ nguồn gốc hệ thống của khối được gỡ; thao tác này không đọc và không cần
	// bất kỳ nguồn cũ nào — khối chỉ-có-ảnh vốn không có nguồn để đọc.
	@Transactional
	SavedSource authorSource(UUID blockPk, String source, String description) {
		FormulaBlock block = this.blocks.findById(blockPk).orElseThrow();
		block.setForm(FormulaBlock.FORM_SOURCE_BACKED);
		block.setSystemOrigin(false);
		block.setSource(source);
		if (description != null) {
			block.setDescription(description);
		}
		block.setUpdatedAt(OffsetDateTime.now());
		FormulaBlock saved = this.blocks.saveAndFlush(block);
		return new SavedSource(saved, pendingRender(saved));
	}

	@Transactional
	FormulaRender reopenRender(UUID blockPk) {
		FormulaBlock block = this.blocks.findById(blockPk).orElseThrow();
		FormulaRender render = pendingRender(block);
		if (FormulaRender.STATUS_FAILED.equals(render.getStatus())) {
			render.setStatus(FormulaRender.STATUS_PENDING);
			render.setErrorCode(null);
			render.setUpdatedAt(OffsetDateTime.now());
			return this.renders.save(render);
		}
		return render;
	}

	// Gắn kết quả chỉ khi revision của khối vẫn khớp revision mà tác vụ render đã khoá: một tác vụ cũ hoàn
	// tất sau khi nguồn đã sửa tiếp không ghi đè được kết quả của revision mới, và bản ghi của nó được đóng
	// dấu lỗi STALE_REVISION để truy vết thay vì biến mất.
	@Transactional
	boolean attachResult(UUID renderId, MathRenderer.RenderedImage image, String actorSub) {
		FormulaRender render = this.renders.findById(renderId).orElseThrow();
		FormulaBlock block = this.blocks.findById(render.getFormulaBlockId()).orElseThrow();
		if (block.getRevision() != render.getSourceRevision()) {
			render.setStatus(FormulaRender.STATUS_FAILED);
			render.setErrorCode("STALE_REVISION");
			render.setUpdatedAt(OffsetDateTime.now());
			this.renders.save(render);
			return false;
		}
		render.setStatus(FormulaRender.STATUS_READY);
		render.setImageBlobId(storeImage(block, render, image, actorSub));
		render.setImageWidth(image.width());
		render.setImageHeight(image.height());
		render.setBaselineOffset(image.baselineOffset());
		render.setRendererVersion(image.rendererVersion());
		render.setRenderConfig(image.renderConfig());
		render.setErrorCode(null);
		render.setUpdatedAt(OffsetDateTime.now());
		this.renders.save(render);
		return true;
	}

	// Ảnh render là một blob của nền tệp: byte được chốt qua cổng lưu trữ ở khoá bất biến do server sinh, và
	// hàng blob ghi trong phân vùng cá nhân của chủ khối — theo khuôn dịch vụ tệp ghi blob trong phân vùng
	// của tài nguyên đích, vì người bấm render có thể là biên tập viên được phân công chứ không nhất thiết
	// là chủ khối. Người tải lên ghi trên blob là chủ khối để truy vết, không mang quyền gì.
	private UUID storeImage(FormulaBlock block, FormulaRender render, MathRenderer.RenderedImage image,
			String actorSub) {
		UUID blobId = UuidV7.generate();
		String storageKey = "blobs/" + blobId;
		String stagingKey = this.storage.stage(render.getId(), image.png());
		try {
			return this.sessions.inPartition("user:" + block.getOwnerSub(), actorSub, () -> {
				this.storage.promote(stagingKey, storageKey);
				FileBlob blob = new FileBlob();
				blob.setId(blobId);
				blob.setStorageKey(storageKey);
				blob.setContentType("image/png");
				blob.setSizeBytes(image.png().length);
				blob.setChecksumSha256(bytesHash(image.png()));
				blob.setWidth(image.width());
				blob.setHeight(image.height());
				blob.setStatus(FileBlob.STATUS_READY);
				blob.setUploadedBySub(block.getOwnerSub());
				OffsetDateTime now = OffsetDateTime.now();
				blob.setCreatedAt(now);
				blob.setUpdatedAt(now);
				this.blobs.save(blob);
				return blobId;
			});
		}
		catch (RuntimeException ex) {
			this.storage.deleteStaged(stagingKey);
			throw ex;
		}
	}

	// Dời nhà cho ảnh render khi khối sao đổi chủ: byte được chốt lại ở khoá bất biến mới và hàng blob ghi
	// trong phân vùng cá nhân của chủ mới với cùng metadata và trạng thái, để đường đọc ảnh theo chủ khối
	// của bản sao thấy đúng blob của nó và vòng đời ảnh của hai bản độc lập. Không định vị được blob
	// nguồn trong phân vùng của chủ nguồn thì giữ nguyên định danh (tham chiếu giữ như trước).
	private UUID rehomeImage(UUID blobId, String sourceOwnerSub, String targetOwnerSub, String actorSub) {
		FileBlob source = this.sessions.inPartition("user:" + sourceOwnerSub, actorSub,
				() -> this.blobs.findById(blobId).orElse(null));
		if (source == null) {
			return blobId;
		}
		byte[] bytes = this.storage.read(source.getStorageKey());
		UUID newId = UuidV7.generate();
		String stagingKey = this.storage.stage(newId, bytes);
		try {
			return this.sessions.inPartition("user:" + targetOwnerSub, actorSub, () -> {
				this.storage.promote(stagingKey, "blobs/" + newId);
				FileBlob blob = new FileBlob();
				blob.setId(newId);
				blob.setStorageKey("blobs/" + newId);
				blob.setContentType(source.getContentType());
				blob.setSizeBytes(source.getSizeBytes());
				blob.setChecksumSha256(source.getChecksumSha256());
				blob.setWidth(source.getWidth());
				blob.setHeight(source.getHeight());
				blob.setStatus(source.getStatus());
				blob.setUploadedBySub(targetOwnerSub);
				OffsetDateTime now = OffsetDateTime.now();
				blob.setCreatedAt(now);
				blob.setUpdatedAt(now);
				this.blobs.save(blob);
				return newId;
			});
		}
		catch (RuntimeException ex) {
			this.storage.deleteStaged(stagingKey);
			throw ex;
		}
	}

	// Đọc byte của ảnh qua hàng blob trong phân vùng của chủ khối, khi lớp dịch vụ đã quyết quyền trên câu
	// hỏi: blob không ở trạng thái sẵn sàng (ví dụ đã bị cách ly) thì coi như không có ảnh — cùng khuôn
	// đường đọc nội dung của dịch vụ tệp.
	byte[] readImage(FormulaRender render, FormulaBlock block, String actorSub) {
		if (render.getImageBlobId() == null) {
			return null;
		}
		return this.sessions.inPartition("user:" + block.getOwnerSub(), actorSub, () -> {
			FileBlob blob = this.blobs.findById(render.getImageBlobId()).orElse(null);
			if (blob == null || !FileBlob.STATUS_READY.equals(blob.getStatus())) {
				return null;
			}
			return this.storage.read(blob.getStorageKey());
		});
	}

	@Transactional
	void markFailed(UUID renderId, String errorCode) {
		this.renders.findById(renderId).ifPresent((render) -> {
			if (!render.ready()) {
				render.setStatus(FormulaRender.STATUS_FAILED);
				render.setErrorCode(errorCode);
				render.setUpdatedAt(OffsetDateTime.now());
				this.renders.save(render);
			}
		});
	}

	private FormulaRender pendingRender(FormulaBlock block) {
		return this.renders.findByFormulaBlockIdAndSourceRevision(block.getId(), block.getRevision())
			.orElseGet(() -> {
				FormulaRender render = new FormulaRender();
				render.setId(UuidV7.generate());
				render.setFormulaBlockId(block.getId());
				render.setSourceRevision(block.getRevision());
				render.setSourceHash(sourceHash(block.getSource()));
				render.setStatus(FormulaRender.STATUS_PENDING);
				render.setRendererVersion("pending");
				render.setRenderConfig("{}");
				OffsetDateTime now = OffsetDateTime.now();
				render.setCreatedAt(now);
				render.setUpdatedAt(now);
				return this.renders.save(render);
			});
	}

	static String sourceHash(String source) {
		return bytesHash((source != null ? source : "").getBytes(StandardCharsets.UTF_8));
	}

	static String bytesHash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Thiếu thuật toán SHA-256.", ex);
		}
	}

}
