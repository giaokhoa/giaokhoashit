package com.giaokhoa.server.access;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoverRequestRepository extends JpaRepository<HandoverRequest, UUID> {
}
