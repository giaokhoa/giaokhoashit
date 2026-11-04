package com.giaokhoa.server.question;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "question_version")
public class QuestionVersion {

	public static final String STATUS_DRAFT = "draft";

	public static final String STATUS_PUBLISHED = "published";

	public static final String TYPE_SINGLE_CHOICE = "single_choice";

	public static final String TYPE_ESSAY_TEXT = "essay_text";

	public static final int CONTENT_CONTRACT_VERSION = 2;

	@Id
	private UUID id;

	@Column(name = "question_id", nullable = false)
	private UUID questionId;

	@Column(name = "owner_sub", nullable = false)
	private String ownerSub;

	@Column(name = "version_number", nullable = false)
	private int versionNumber;

	@Column(nullable = false)
	private String status;

	// Dấu khoá: đặt khi phát hành và không bao giờ mở lại. Tính bất biến của một phiên bản đọc từ chính cột này,
	// không suy từ grant hay trạng thái ở nơi khác.
	@Column(name = "locked_at")
	private OffsetDateTime lockedAt;

	@Column(name = "question_type", nullable = false)
	private String questionType;

	@Column(name = "content_contract_version", nullable = false)
	private int contentContractVersion;

	@Column(nullable = false)
	private String stem;

	// Danh sách lựa chọn của trắc nghiệm, lưu dạng JSON gồm các phần tử {id, text}; tự luận để trống.
	@Column
	private String choices;

	// Đáp án, lời giải và tiêu chí chấm là phần chỉ hiện khi người gọi có view_answer trên đúng phiên bản này.
	@Column(name = "correct_choice_id")
	private String correctChoiceId;

	@Column
	private String solution;

	@Column
	private String rubric;

	@Column(name = "content_hash", nullable = false)
	private String contentHash;

	// Khoá lạc quan: Hibernate tự tăng cột này ở mỗi lượt ghi và chặn ghi đè đồng thời; ứng dụng không tự đặt
	// giá trị của nó (xem chương Locking của tài liệu Hibernate ORM).
	@Version
	@Column(nullable = false)
	private int revision;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public boolean locked() {
		return this.lockedAt != null;
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getQuestionId() {
		return this.questionId;
	}

	public void setQuestionId(UUID questionId) {
		this.questionId = questionId;
	}

	public String getOwnerSub() {
		return this.ownerSub;
	}

	public void setOwnerSub(String ownerSub) {
		this.ownerSub = ownerSub;
	}

	public int getVersionNumber() {
		return this.versionNumber;
	}

	public void setVersionNumber(int versionNumber) {
		this.versionNumber = versionNumber;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public OffsetDateTime getLockedAt() {
		return this.lockedAt;
	}

	public void setLockedAt(OffsetDateTime lockedAt) {
		this.lockedAt = lockedAt;
	}

	public String getQuestionType() {
		return this.questionType;
	}

	public void setQuestionType(String questionType) {
		this.questionType = questionType;
	}

	public int getContentContractVersion() {
		return this.contentContractVersion;
	}

	public void setContentContractVersion(int contentContractVersion) {
		this.contentContractVersion = contentContractVersion;
	}

	public String getStem() {
		return this.stem;
	}

	public void setStem(String stem) {
		this.stem = stem;
	}

	public String getChoices() {
		return this.choices;
	}

	public void setChoices(String choices) {
		this.choices = choices;
	}

	public String getCorrectChoiceId() {
		return this.correctChoiceId;
	}

	public void setCorrectChoiceId(String correctChoiceId) {
		this.correctChoiceId = correctChoiceId;
	}

	public String getSolution() {
		return this.solution;
	}

	public void setSolution(String solution) {
		this.solution = solution;
	}

	public String getRubric() {
		return this.rubric;
	}

	public void setRubric(String rubric) {
		this.rubric = rubric;
	}

	public String getContentHash() {
		return this.contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public int getRevision() {
		return this.revision;
	}

	public OffsetDateTime getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(OffsetDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(OffsetDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
