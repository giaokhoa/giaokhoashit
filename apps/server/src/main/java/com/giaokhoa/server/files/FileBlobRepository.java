package com.giaokhoa.server.files;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FileBlobRepository extends JpaRepository<FileBlob, UUID> {

	@Query("select coalesce(sum(b.sizeBytes), 0) from FileBlob b where b.uploadedBySub = ?1")
	long sumSizeByUploader(String uploaderSub);

	@Query("select coalesce(sum(b.sizeBytes), 0) from FileBlob b")
	long sumSizeInPartition();

	List<FileBlob> findByStatus(String status);

	List<FileBlob> findByGcMarkedAtIsNotNullAndGcMarkedAtBefore(OffsetDateTime gcMarkedAt);

}
