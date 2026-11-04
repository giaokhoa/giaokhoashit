package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.giaokhoa.server.access.AccessDecisionService.Decision;
import com.giaokhoa.server.access.AccessDecisionService.Located;
import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.stereotype.Service;

/**
 * Bàn giao chính thức tài nguyên từ kho cá nhân của giáo viên vào một trung tâm: trung tâm tạo yêu cầu, giáo viên
 * được nêu tên chọn phiên bản và nộp, API kiểm tra quyền {@code submit} trên từng mục ngay tại thời điểm nộp và —
 * chỉ khi mọi mục đều đạt — tạo các bản thuộc trung tâm kèm một biên nhận bất biến trong cùng một đơn vị ghi của
 * phân vùng trung tâm. Yêu cầu nộp không cấp cho trung tâm quyền đọc nào trên kho cá nhân của giáo viên, và một
 * mục không đạt làm toàn bộ lần nộp bị từ chối mà không tạo dữ kiện một phần nào.
 */
@Service
public class HandoverService {

	public record CreateHandoverRequest(String requestedTeacherSub, String resourceTypes,
			UUID destinationCollectionId, String reuseRights) {
	}

	public record SubmitItemRequest(UUID sourceResourceId, UUID sourceVersionId) {
	}

	public record SubmitRequest(List<SubmitItemRequest> items) {
	}

	public record ReviewRequest(String decision) {
	}

	public record HandoverRequestView(UUID requestId, String organizationId, String requestedTeacherSub,
			String resourceTypes, UUID destinationCollectionId, String reuseRights, String status) {
	}

	public record SubmissionItemView(UUID sourceResourceId, UUID sourceVersionId, UUID receivedResourceId,
			UUID receivedVersionId) {
	}

	public record SubmissionView(UUID submissionId, UUID handoverRequestId, String submitterSub,
			OffsetDateTime submittedAt, List<SubmissionItemView> items) {
	}

	private record DecidedItem(AccessResource source, ResourceVersion sourceVersion) {
	}

	private final HandoverRequestRepository requests;

	private final SubmissionRepository submissions;

	private final SubmissionItemRepository submissionItems;

	private final AccessResourceRepository resources;

	private final ResourceVersionRepository versions;

	private final AccessDecisionService decisions;

	private final PartitionSessions sessions;

	public HandoverService(HandoverRequestRepository requests, SubmissionRepository submissions,
			SubmissionItemRepository submissionItems, AccessResourceRepository resources,
			ResourceVersionRepository versions, AccessDecisionService decisions, PartitionSessions sessions) {
		this.requests = requests;
		this.submissions = submissions;
		this.submissionItems = submissionItems;
		this.resources = resources;
		this.versions = versions;
		this.decisions = decisions;
		this.sessions = sessions;
	}

	public HandoverRequestView createRequest(TenantContext context, CreateHandoverRequest request) {
		if (!this.decisions.isTenantAdmin(context)) {
			throw AccessException.forbidden("Chỉ tenant-admin của trung tâm mới tạo được yêu cầu nộp");
		}
		if (request.requestedTeacherSub() == null || request.requestedTeacherSub().isBlank()) {
			throw AccessException.validation("requestedTeacherSub là bắt buộc");
		}
		return this.sessions.inPartition(context.organizationId(), context.sub(), () -> {
			if (request.destinationCollectionId() != null) {
				this.resources.findById(request.destinationCollectionId())
						.orElseThrow(() -> AccessException.notFound("Không tìm thấy bộ sưu tập đích"));
			}
			OffsetDateTime now = OffsetDateTime.now();
			HandoverRequest row = new HandoverRequest();
			row.setId(UUID.randomUUID());
			row.setOrganizationId(context.organizationId());
			row.setRequestedTeacherSub(request.requestedTeacherSub());
			row.setResourceTypes(request.resourceTypes());
			row.setDestinationCollectionId(request.destinationCollectionId());
			row.setReuseRights(request.reuseRights());
			row.setStatus(HandoverRequest.STATUS_OPEN);
			row.setCreatedBySub(context.sub());
			row.setCreatedAt(now);
			row.setUpdatedAt(now);
			return view(this.requests.save(row));
		});
	}

	public SubmissionView submit(TenantContext context, Set<String> tokenOrganizationIds, UUID requestId,
			SubmitRequest request) {
		if (request.items() == null || request.items().isEmpty()) {
			throw AccessException.validation("Lần nộp phải có ít nhất một mục");
		}
		HandoverRequest handover = null;
		String organizationPartition = null;
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		if (this.decisions.isCenterMember(context)) {
			candidates.add(context.organizationId());
		}
		candidates.addAll(tokenOrganizationIds);
		for (String partition : candidates) {
			HandoverRequest found = this.sessions.inPartition(partition, context.sub(),
					() -> this.requests.findById(requestId).orElse(null));
			if (found != null) {
				handover = found;
				organizationPartition = partition;
				break;
			}
		}
		if (handover == null) {
			throw AccessException.notFound("Không tìm thấy yêu cầu nộp");
		}
		if (!context.sub().equals(handover.getRequestedTeacherSub())) {
			throw AccessException.forbidden("Chỉ giáo viên được nêu tên trong yêu cầu mới nộp được");
		}
		if (!HandoverRequest.STATUS_OPEN.equals(handover.getStatus())) {
			throw AccessException.stateConflict("Yêu cầu nộp đã đóng");
		}
		Set<String> wanted = new LinkedHashSet<>();
		for (SubmitItemRequest item : request.items()) {
			wanted.add(item.sourceResourceId() + ":" + item.sourceVersionId());
		}
		String partition = organizationPartition;
		List<Submission> existing = this.sessions.inPartition(partition, context.sub(),
				() -> this.submissions.findByHandoverRequestIdAndSubmitterSub(requestId, context.sub()));
		for (Submission candidate : existing) {
			List<SubmissionItem> rows = this.sessions.inPartition(partition, context.sub(),
					() -> this.submissionItems.findBySubmissionId(candidate.getId()));
			Set<String> have = new LinkedHashSet<>();
			for (SubmissionItem row : rows) {
				have.add(row.getSourceResourceId() + ":" + row.getSourceVersionId());
			}
			if (have.equals(wanted)) {
				return view(candidate, rows);
			}
		}
		List<DecidedItem> decided = new ArrayList<>();
		for (SubmitItemRequest item : request.items()) {
			Decision decision = this.decisions.decide(context, item.sourceResourceId(), item.sourceVersionId(),
					AccessAction.SUBMIT);
			if (!decision.allowed()) {
				throw AccessCheckService.mapDenial(decision);
			}
			Located located = this.decisions.locate(context, item.sourceResourceId());
			ResourceVersion sourceVersion = this.sessions.inPartition(located.partitionKey(), context.sub(),
					() -> this.versions.findByIdAndResourceId(item.sourceVersionId(), item.sourceResourceId())
							.orElse(null));
			decided.add(new DecidedItem(located.resource(), sourceVersion));
		}
		HandoverRequest target = handover;
		return this.sessions.inPartition(partition, context.sub(), () -> {
			OffsetDateTime now = OffsetDateTime.now();
			Submission submission = new Submission();
			submission.setId(UUID.randomUUID());
			submission.setHandoverRequestId(requestId);
			submission.setSubmitterSub(context.sub());
			submission.setSubmittedAt(now);
			submission.setCreatedAt(now);
			submission.setUpdatedAt(now);
			this.submissions.save(submission);
			List<SubmissionItem> rows = new ArrayList<>();
			for (DecidedItem item : decided) {
				AccessResource received = new AccessResource();
				received.setId(UUID.randomUUID());
				received.setResourceType(item.source().getResourceType());
				received.setWorkspaceKind(AccessResource.WORKSPACE_CENTER);
				received.setOwnerType(AccessResource.OWNER_ORGANIZATION);
				received.setOwnerOrganizationId(partition);
				received.setCollectionId(target.getDestinationCollectionId());
				received.setCustodyKind(AccessResource.CUSTODY_OWNED);
				received.setSourceResourceId(item.source().getId());
				received.setSourceVersionId(item.sourceVersion().getId());
				received.setInheritedUsageLimits(item.source().getInheritedUsageLimits());
				received.setBusinessCondition(AccessResource.CONDITION_PENDING_REVIEW);
				received.setCreatedBySub(context.sub());
				received.setCreatedAt(now);
				received.setUpdatedAt(now);
				this.resources.save(received);
				ResourceVersion receivedVersion = AccessCheckService.newVersion(received, 1,
						item.sourceVersion().getContentHash(), context.sub(), now);
				this.versions.save(receivedVersion);
				SubmissionItem row = new SubmissionItem();
				row.setId(UUID.randomUUID());
				row.setSubmissionId(submission.getId());
				row.setSourceResourceId(item.source().getId());
				row.setSourceVersionId(item.sourceVersion().getId());
				row.setReceivedResourceId(received.getId());
				row.setReceivedVersionId(receivedVersion.getId());
				row.setCreatedAt(now);
				row.setUpdatedAt(now);
				rows.add(this.submissionItems.save(row));
			}
			return view(submission, rows);
		});
	}

	public SubmissionView review(TenantContext context, UUID submissionId, ReviewRequest request) {
		if (!this.decisions.isCenterMember(context)) {
			throw AccessException.forbidden("Người gọi không phải thành viên của trung tâm tiếp nhận");
		}
		boolean accept;
		if ("accept".equals(request.decision())) {
			accept = true;
		}
		else if ("reject".equals(request.decision())) {
			accept = false;
		}
		else {
			throw AccessException.validation("decision phải là accept hoặc reject");
		}
		String partition = context.organizationId();
		Submission submission = this.sessions.inPartition(partition, context.sub(),
				() -> this.submissions.findById(submissionId).orElse(null));
		if (submission == null) {
			throw AccessException.notFound("Không tìm thấy biên nhận nộp");
		}
		List<SubmissionItem> rows = this.sessions.inPartition(partition, context.sub(),
				() -> this.submissionItems.findBySubmissionId(submissionId));
		for (SubmissionItem row : rows) {
			Located located = this.decisions.locate(context, row.getReceivedResourceId());
			if (located == null || !this.decisions.hasReviewSource(context, located.resource())) {
				throw AccessException.forbidden("Người gọi không có nguồn quyền duyệt bản đã nhận này");
			}
		}
		String condition = accept ? AccessResource.CONDITION_ACCEPTED : AccessResource.CONDITION_REJECTED;
		this.sessions.inPartition(partition, context.sub(), () -> {
			OffsetDateTime now = OffsetDateTime.now();
			for (SubmissionItem row : rows) {
				AccessResource received = this.resources.findById(row.getReceivedResourceId()).orElse(null);
				if (received != null) {
					received.setBusinessCondition(condition);
					received.setUpdatedAt(now);
					this.resources.save(received);
				}
			}
		});
		return view(submission, rows);
	}

	private static HandoverRequestView view(HandoverRequest row) {
		return new HandoverRequestView(row.getId(), row.getOrganizationId(), row.getRequestedTeacherSub(),
				row.getResourceTypes(), row.getDestinationCollectionId(), row.getReuseRights(), row.getStatus());
	}

	private static SubmissionView view(Submission submission, List<SubmissionItem> rows) {
		List<SubmissionItemView> items = new ArrayList<>();
		for (SubmissionItem row : rows) {
			items.add(new SubmissionItemView(row.getSourceResourceId(), row.getSourceVersionId(),
					row.getReceivedResourceId(), row.getReceivedVersionId()));
		}
		return new SubmissionView(submission.getId(), submission.getHandoverRequestId(), submission.getSubmitterSub(),
				submission.getSubmittedAt(), items);
	}

}
