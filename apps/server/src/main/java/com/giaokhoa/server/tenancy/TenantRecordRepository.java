package com.giaokhoa.server.tenancy;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRecordRepository extends JpaRepository<TenantRecord, UUID> {
}
