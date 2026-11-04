package com.giaokhoa.server.files;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FileBlobRepository extends JpaRepository<FileBlob, UUID> {

	@Query("select coalesce(sum(b.sizeBytes), 0) from FileBlob b where b.uploadedBySub = ?1")
	long sumSizeByUploader(String uploaderSub);

	@Query("select coalesce(sum(b.sizeBytes), 0) from FileBlob b")
	long sumSizeInPartition();

}
