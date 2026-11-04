package com.giaokhoa.server.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;

import com.giaokhoa.server.question.Question;
import com.giaokhoa.server.question.QuestionRepository;
import com.giaokhoa.server.question.QuestionRights;
import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.QuestionVersionRepository;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

// Kiểm chứng tầng quyết định của lát đính kèm bằng một điểm nối quyền stub: người xem có view_content nhưng
// không có view_answer. Đây là ca tách quyền tới từng tệp mà nguồn quyền chủ sở hữu hiện hành chưa tạo được
// trên đường HTTP (chủ sở hữu luôn có cả hai quyền), nên ca được kiểm tại đúng điểm quyết định dùng chung.
class AttachmentServiceTests {

	private AttachmentStore store;

	private AttachmentService service;

	@BeforeEach
	void setUp() {
		this.store = new LocalAttachmentStore();
		Question question = new Question();
		question.setId(UUID.randomUUID());
		question.setOwnerSub("chu-so-huu");
		QuestionVersion version = new QuestionVersion();
		version.setId(UUID.randomUUID());
		version.setQuestionId(question.getId());
		QuestionRepository questions = mock(QuestionRepository.class);
		when(questions.findById(question.getId())).thenReturn(Optional.of(question));
		QuestionVersionRepository versions = mock(QuestionVersionRepository.class);
		when(versions.findByIdAndQuestionId(version.getId(), question.getId())).thenReturn(Optional.of(version));
		QuestionRights rights = new QuestionRights() {
			@Override
			public boolean personalAuthor(Jwt jwt) {
				return true;
			}

			@Override
			public boolean allowed(TenantContext context, Question q, QuestionVersion v, String action) {
				return !QuestionRights.VIEW_ANSWER.equals(action);
			}
		};
		this.service = new AttachmentService(this.store, questions, versions, rights, 5L * 1024 * 1024, 20_000_000L,
				24L);
		TenantContexts.set(new TenantContext("nguoi-xem", null, null, List.of(), false, false, "user:nguoi-xem"));
		this.questionId = question.getId();
		this.versionId = version.getId();
	}

	private UUID questionId;

	private UUID versionId;

	@AfterEach
	void tearDown() {
		TenantContexts.clear();
	}

	@Test
	@DisplayName("Định danh phiên, blob và liên kết đều là UUIDv7")
	void identifiersAreUuidV7() throws IOException {
		AttachmentService.LinkView link = attach("content");
		AttachmentStore.Link stored = this.store.findLink(link.linkId()).orElseThrow();
		assertEquals(7, stored.id().version());
		assertEquals(7, stored.blobId().version());
		assertEquals(7, stored.sessionId().version());
	}

	@Test
	@DisplayName("Người xem không có view_answer không thấy và không đọc được tệp ở vị trí đáp án, nhưng đọc được tệp nội dung")
	void answerFilesAreSeparatedPerFile() throws IOException {
		AttachmentService.LinkView answer = attach("answer");
		AttachmentService.LinkView content = attach("content");

		List<AttachmentService.LinkView> listed = this.service.listLinks(this.questionId, this.versionId);
		assertEquals(List.of(content.linkId()), listed.stream().map(AttachmentService.LinkView::linkId).toList());

		assertEquals("RESOURCE_NOT_FOUND", assertThrows(AttachmentException.class,
				() -> this.service.readContent(this.questionId, this.versionId, answer.linkId())).code());
		assertTrue(this.service.readContent(this.questionId, this.versionId, content.linkId()).bytes().length > 0);
	}

	private AttachmentService.LinkView attach(String position) throws IOException {
		AttachmentService.SessionView session = this.service.openSession(this.questionId, this.versionId,
				new AttachmentService.OpenSessionRequest(position, null, "anh-" + position + ".png"));
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		this.service.uploadContent(session.sessionId(),
				new MockMultipartFile("file", "anh.png", "image/png", out.toByteArray()));
		return this.service.complete(session.sessionId(), null);
	}

}
