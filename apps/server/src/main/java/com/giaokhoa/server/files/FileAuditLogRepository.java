package com.giaokhoa.server.files;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileAuditLogRepository extends JpaRepository<FileAuditLog, UUID> {

}
