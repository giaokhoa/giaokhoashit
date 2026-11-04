package com.giaokhoa.server.tenancy;

/**
 * Giữ {@link TenantContext} của request đang chạy trên thread hiện tại: filter ngữ cảnh đặt vào sau khi JWT được
 * kiểm chứng, resolver của Hibernate và điểm đặt biến RLS đọc ra, filter xoá khi request kết thúc để thread trong
 * pool không rò ngữ cảnh sang request khác.
 */
public final class TenantContexts {

	private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

	private TenantContexts() {
	}

	public static void set(TenantContext context) {
		CURRENT.set(context);
	}

	public static TenantContext current() {
		return CURRENT.get();
	}

	public static void clear() {
		CURRENT.remove();
	}

}
