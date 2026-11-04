package com.giaokhoa.server.attachment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Bản cài đặt trong bộ nhớ của cổng kho, chỉ còn dùng làm double cho các test tầng dịch vụ của lát đính kèm:
// đường chạy thật dùng FoundationAttachmentStore trên nền tệp L1, còn test tầng dịch vụ cần một cổng kho nhẹ
// không dính DB để kiểm đúng các quyết định quyền và trạng thái của AttachmentService.
class LocalAttachmentStore implements AttachmentStore {

	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

	private final Map<UUID, Blob> blobs = new ConcurrentHashMap<>();

	private final Map<UUID, byte[]> blobBytes = new ConcurrentHashMap<>();

	private final Map<UUID, byte[]> stagedBytes = new ConcurrentHashMap<>();

	private final Map<UUID, Link> links = new ConcurrentHashMap<>();

	@Override
	public Session saveSession(Session session) {
		this.sessions.put(session.id(), session);
		return session;
	}

	@Override
	public Optional<Session> findSession(UUID sessionId) {
		return Optional.ofNullable(this.sessions.get(sessionId));
	}

	@Override
	public List<Session> findSessionsForVersion(UUID versionId) {
		return this.sessions.values().stream().filter((session) -> session.versionId().equals(versionId)).toList();
	}

	@Override
	public Blob saveBlob(Blob blob, byte[] bytes) {
		this.blobs.put(blob.id(), blob);
		this.blobBytes.put(blob.id(), bytes);
		return blob;
	}

	@Override
	public Optional<Blob> findBlob(UUID blobId) {
		return Optional.ofNullable(this.blobs.get(blobId));
	}

	@Override
	public Optional<byte[]> readBlob(UUID blobId) {
		return Optional.ofNullable(this.blobBytes.get(blobId));
	}

	@Override
	public void stage(UUID sessionId, byte[] bytes) {
		this.stagedBytes.put(sessionId, bytes);
	}

	@Override
	public Optional<byte[]> staged(UUID sessionId) {
		return Optional.ofNullable(this.stagedBytes.get(sessionId));
	}

	@Override
	public void dropStaged(UUID sessionId) {
		this.stagedBytes.remove(sessionId);
	}

	@Override
	public Link saveLink(Link link) {
		this.links.put(link.id(), link);
		return link;
	}

	@Override
	public Optional<Link> findLink(UUID linkId) {
		return Optional.ofNullable(this.links.get(linkId));
	}

	@Override
	public List<Link> findLinksForVersion(UUID versionId) {
		return this.links.values().stream().filter((link) -> link.versionId().equals(versionId)).toList();
	}

}
