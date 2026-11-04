package com.giaokhoa.server.tenancy;

/**
 * Resolver không xác minh được membership hiện hành của Organization của trang: request bị từ chối thay vì mở chế
 * độ trung tâm theo claim trong token (fail-closed).
 */
public class MembershipVerificationException extends RuntimeException {

	public MembershipVerificationException(String organizationId) {
		super("Không xác minh được membership hiện hành của Organization " + organizationId);
	}

}
