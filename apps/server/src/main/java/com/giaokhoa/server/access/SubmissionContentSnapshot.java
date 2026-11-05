package com.giaokhoa.server.access;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.giaokhoa.server.attachment.AttachmentStore;
import com.giaokhoa.server.exam.Exam;
import com.giaokhoa.server.exam.ExamItem;
import com.giaokhoa.server.exam.ExamItemRepository;
import com.giaokhoa.server.exam.ExamRepository;
import com.giaokhoa.server.files.FileLink;
import com.giaokhoa.server.files.FileLinkRepository;
import com.giaokhoa.server.math.MathFormulaService;
import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.QuestionVersionBlock;
import com.giaokhoa.server.question.QuestionVersionBlockRepository;
import com.giaokhoa.server.question.QuestionVersionRepository;
import com.giaokhoa.server.tenancy.TenantContext;

/**
 * Đóng băng nội dung của một phiên bản nguồn tại thời điểm nộp thành dữ kiện của bản nhận thuộc trung tâm.
 * Bản nhận chỉ giữ mặt trình bày: văn bản, khối ở dạng chỉ-có-ảnh cùng tham chiếu ảnh render, và các tệp qua
 * liên kết mới — nguồn LaTeX không vào bản nhận ở bất kỳ khối nào, kể cả khối do chính người nộp tự soạn, vì
 * nộp là chuyển chủ và bản 07 không có cơ chế bàn giao nguồn. Phần đáp án (đáp án đúng, lời giải, tiêu chí
 * chấm, khối và tệp thuộc phần đáp án) chỉ vào bản nhận khi người nộp có view_answer trên đúng phiên bản đó;
 * thiếu quyền thì phần đáp án bị lược khỏi cả dữ kiện đóng băng lẫn tập liên kết sẽ tạo cho bản nhận.
 */
@Component
class SubmissionContentSnapshot {

	// Một tệp của bản nhận sau khi đóng băng: blob đã được dời nhà vào phân vùng của trung tâm nhận, nên
	// liên kết mới của bản nhận trỏ tới blob thuộc chính phân vùng của nó và đọc được qua đường tệp của
	// trung tâm kể cả khi phía cá nhân bị xoá hay thu hồi sau đó.
	record FrozenFile(String position, String blockId, UUID blobId, String displayName) {
	}

	record FrozenContent(String json, List<FrozenFile> files) {
	}

	private final AccessDecisionService decisions;

	private final PartitionSessions sessions;

	private final QuestionVersionRepository questionVersions;

	private final QuestionVersionBlockRepository questionBlocks;

	private final FileLinkRepository links;

	private final ExamRepository exams;

	private final ExamItemRepository examItems;

	private final MathFormulaService formulas;

	private final AttachmentStore attachmentStore;

	private final ObjectMapper objectMapper;

	SubmissionContentSnapshot(AccessDecisionService decisions, PartitionSessions sessions,
			QuestionVersionRepository questionVersions, QuestionVersionBlockRepository questionBlocks,
			FileLinkRepository links, ExamRepository exams, ExamItemRepository examItems,
			MathFormulaService formulas, AttachmentStore attachmentStore, ObjectMapper objectMapper) {
		this.decisions = decisions;
		this.sessions = sessions;
		this.questionVersions = questionVersions;
		this.questionBlocks = questionBlocks;
		this.links = links;
		this.exams = exams;
		this.examItems = examItems;
		this.formulas = formulas;
		this.attachmentStore = attachmentStore;
		this.objectMapper = objectMapper;
	}

	FrozenContent freeze(TenantContext context, AccessResource source, ResourceVersion sourceVersion,
			String sourcePartition, String targetPartition) {
		if ("question".equals(source.getResourceType())) {
			// Tài nguyên ở lõi mang nhãn loại câu hỏi nhưng không có dữ kiện của feat câu hỏi (tạo trực tiếp
			// ở lõi) thì không có gì để đóng băng: bản nhận giữ nguyên dạng vỏ như trước, không chặn lần nộp.
			QuestionVersion version = this.questionVersions.findById(sourceVersion.getId()).orElse(null);
			if (version == null) {
				return new FrozenContent(null, List.of());
			}
			boolean includeAnswers = this.decisions
					.decide(context, source.getId(), sourceVersion.getId(), AccessAction.VIEW_ANSWER).allowed();
			List<FrozenFile> files = new ArrayList<>();
			ObjectNode node = questionNode(context, version, sourcePartition, targetPartition, includeAnswers,
					files, new java.util.HashMap<>());
			node.put("type", "question");
			return new FrozenContent(writeJson(node), List.copyOf(files));
		}
		if ("exam".equals(source.getResourceType())) {
			boolean examExists = this.sessions.inPartition(sourcePartition, context.sub(),
					() -> this.exams.findById(source.getId()).isPresent());
			if (!examExists) {
				return new FrozenContent(null, List.of());
			}
			List<ExamItem> items = this.sessions.inPartition(sourcePartition, context.sub(),
					() -> this.examItems.findByExamVersionIdOrderByPositionAsc(sourceVersion.getId()));
			String title = this.sessions.inPartition(sourcePartition, context.sub(),
					() -> this.exams.findById(source.getId()).map(Exam::getTitle).orElse(null));
			ObjectNode node = this.objectMapper.createObjectNode();
			node.put("type", "exam");
			node.put("title", title);
			ArrayNode itemNodes = node.putArray("items");
			List<FrozenFile> files = new ArrayList<>();
			java.util.Map<UUID, UUID> rehomed = new java.util.HashMap<>();
			for (ExamItem item : items) {
				QuestionVersion component = this.questionVersions.findById(item.getQuestionVersionId())
						.orElseThrow(() -> AccessException.stateConflict(
								"Không đọc được phiên bản câu thành phần của đề để đóng băng bản nhận."));
				boolean includeAnswers = this.decisions.decide(context, component.getQuestionId(),
						component.getId(), AccessAction.VIEW_ANSWER).allowed();
				List<FrozenFile> itemFiles = new ArrayList<>();
				ObjectNode itemNode = questionNode(context, component, sourcePartition, targetPartition,
						includeAnswers, itemFiles, rehomed);
				itemNode.put("position", item.getPosition());
				itemNode.put("score", item.getScore());
				itemNodes.add(itemNode);
				files.addAll(itemFiles);
			}
			return new FrozenContent(writeJson(node), List.copyOf(files));
		}
		return new FrozenContent(null, List.of());
	}

	// Nội dung đóng băng của một phiên bản câu: cột nội dung cũ, khối nội dung và khối công thức đều ở dạng
	// chỉ-có-ảnh; các tệp đủ điều kiện (tệp đính kèm và ảnh render của khối công thức) được gom vào files
	// với blob đã dời nhà vào phân vùng của bản nhận để phía gọi tạo liên kết mới, và cũng được ghi vào
	// chính nút này như một bản kê blob đã đóng băng.
	private ObjectNode questionNode(TenantContext context, QuestionVersion version, String sourcePartition,
			String targetPartition, boolean includeAnswers, List<FrozenFile> files,
			java.util.Map<UUID, UUID> rehomed) {
		UUID questionId = version.getQuestionId();
		UUID versionId = version.getId();
		ObjectNode node = this.objectMapper.createObjectNode();
		node.put("questionVersionId", versionId.toString());
		node.put("questionType", version.getQuestionType());
		node.put("stem", version.getStem());
		if (version.getChoices() != null) {
			try {
				node.set("choices", this.objectMapper.readTree(version.getChoices()));
			}
			catch (JacksonException ex) {
				throw AccessException.stateConflict("Dữ kiện lựa chọn của phiên bản nguồn không đọc được.");
			}
		}
		if (includeAnswers) {
			node.put("correctChoiceId", version.getCorrectChoiceId());
			node.put("solution", version.getSolution());
			node.put("rubric", version.getRubric());
		}
		ArrayNode blockNodes = node.putArray("blocks");
		for (QuestionVersionBlock block : this.questionBlocks.findByQuestionVersionId(versionId)) {
			if (!includeAnswers && block.answerArea()) {
				continue;
			}
			ObjectNode blockNode = blockNodes.addObject();
			blockNode.put("part", block.getPart());
			blockNode.put("choiceId", block.getChoiceId());
			blockNode.put("position", block.getPosition());
			blockNode.put("kind", block.getKind());
			blockNode.put("text", block.getTextContent());
			blockNode.put("description", block.getDescription());
			if (QuestionVersionBlock.KIND_FORMULA.equals(block.getKind())) {
				blockNode.put("form", QuestionVersionBlock.FORM_RENDER_ONLY);
				blockNode.put("systemOrigin", block.isSystemOrigin());
				if (block.getImageBlobId() != null) {
					blockNode.put("imageBlobId", block.getImageBlobId().toString());
					blockNode.put("imageWidth", block.getImageWidth());
					blockNode.put("imageHeight", block.getImageHeight());
					blockNode.put("baselineOffset", block.getBaselineOffset());
				}
			}
		}
		ArrayNode formulaNodes = node.putArray("formulas");
		List<FrozenFile> formulaFiles = new ArrayList<>();
		for (MathFormulaService.FormulaSnapshot formula : this.formulas.snapshotBlocks(versionId)) {
			if (!includeAnswers && formula.answerBearing()) {
				continue;
			}
			ObjectNode formulaNode = formulaNodes.addObject();
			formulaNode.put("blockId", formula.blockId());
			formulaNode.put("section", formula.section());
			formulaNode.put("position", formula.position());
			formulaNode.put("form", QuestionVersionBlock.FORM_RENDER_ONLY);
			formulaNode.put("display", formula.display());
			formulaNode.put("systemOrigin", formula.systemOrigin());
			formulaNode.put("description", formula.description());
			if (formula.image() != null) {
				ObjectNode image = formulaNode.putObject("image");
				image.put("fileRef", formula.image().fileRef());
				UUID imageBlobId = formula.image().imageBlobId();
				if (imageBlobId != null) {
					// Ảnh render cũng là tệp của bản nhận (M1: bản nhận giữ ảnh): blob được dời nhà vào
					// phân vùng của bản nhận và một liên kết mới được tạo cho nó, để trung tâm đọc được
					// ảnh qua đường tệp của bản nhận thay vì phụ thuộc vào blob phía cá nhân.
					UUID receiptBlobId = rehomed.computeIfAbsent(imageBlobId,
							(id) -> this.attachmentStore.rehomeBlob(id, sourcePartition, targetPartition));
					image.put("imageBlobId", receiptBlobId.toString());
					formulaFiles.add(new FrozenFile(filePositionForSection(formula.section()), formula.blockId(),
							receiptBlobId,
							formula.description() != null ? formula.description() : "anh-cong-thuc"));
				}
				else {
					image.put("imageBlobId", (String) null);
				}
				image.put("width", formula.image().width());
				image.put("height", formula.image().height());
				image.put("baselineOffset", formula.image().baselineOffset());
			}
		}
		List<FileLink> sourceLinks = this.sessions.inPartition(sourcePartition, context.sub(),
				() -> this.links.findByResourceVersionIdOrderByCreatedAtAsc(versionId));
		ArrayNode fileNodes = node.putArray("files");
		for (FileLink link : sourceLinks) {
			if (!link.getResourceId().equals(questionId)) {
				continue;
			}
			if (!includeAnswers && link.answerArea()) {
				continue;
			}
			UUID receiptBlobId = rehomed.computeIfAbsent(link.getBlobId(),
					(id) -> this.attachmentStore.rehomeBlob(id, sourcePartition, targetPartition));
			files.add(new FrozenFile(link.getPosition(), link.getBlockId(), receiptBlobId,
					link.getDisplayName()));
			ObjectNode fileNode = fileNodes.addObject();
			fileNode.put("blobId", receiptBlobId.toString());
			fileNode.put("position", link.getPosition());
			fileNode.put("blockId", link.getBlockId());
			fileNode.put("displayName", link.getDisplayName());
		}
		for (FrozenFile formulaFile : formulaFiles) {
			files.add(formulaFile);
			ObjectNode fileNode = fileNodes.addObject();
			fileNode.put("blobId", formulaFile.blobId().toString());
			fileNode.put("position", formulaFile.position());
			fileNode.put("blockId", formulaFile.blockId());
			fileNode.put("displayName", formulaFile.displayName());
		}
		return node;
	}

	// Vị trí tệp của ảnh render suy từ phần nội dung của khối: phần lời giải và tiêu chí chấm là vùng đáp
	// án, phần thân và lựa chọn là vùng nội dung — cùng cách phân vùng của liên kết tệp đính kèm.
	private static String filePositionForSection(String section) {
		return switch (section) {
			case "solution", "rubric" -> "solution";
			default -> "content";
		};
	}

	private String writeJson(ObjectNode node) {
		try {
			return this.objectMapper.writeValueAsString(node);
		}
		catch (JacksonException ex) {
			throw AccessException.stateConflict("Không ghi được nội dung đóng băng của bản nhận.");
		}
	}

}
