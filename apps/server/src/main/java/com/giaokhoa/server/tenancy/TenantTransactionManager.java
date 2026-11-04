package com.giaokhoa.server.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.Session;

import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Điểm chặn duy nhất đặt biến ngữ cảnh cho Row Level Security ở đầu mỗi giao dịch của ứng dụng, gồm cả các giao
 * dịch lồng {@code REQUIRES_NEW}: ngay sau khi giao dịch bắt đầu, ghi {@code app.current_sub} (luôn đặt) và
 * {@code app.partition_key} (chỉ khi ngữ cảnh có khoá phân vùng) từ chính {@link TenantContext} đã resolve, bằng
 * {@code set_config(..., true)} tương đương SET LOCAL để giá trị tự hết hiệu lực khi giao dịch kết thúc và không rò
 * sang request khác dùng lại connection trong pool.
 */
public class TenantTransactionManager extends JpaTransactionManager {

	public TenantTransactionManager(EntityManagerFactory entityManagerFactory) {
		super(entityManagerFactory);
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		super.doBegin(transaction, definition);
		if (TransactionSynchronizationManager
				.getResource(getEntityManagerFactory()) instanceof EntityManagerHolder holder) {
			holder.getEntityManager().unwrap(Session.class).doWork(this::applyRlsContext);
		}
	}

	private void applyRlsContext(Connection connection) throws SQLException {
		// RLS và set_config là cơ chế của riêng PostgreSQL; các database khác (H2 ở bộ test) không có lớp bảo vệ này.
		if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
			return;
		}
		TenantContext context = TenantContexts.current();
		if (context == null) {
			return;
		}
		setConfig(connection, "app.current_sub", context.sub());
		if (context.partitionKey() != null) {
			setConfig(connection, "app.partition_key", context.partitionKey());
		}
	}

	private void setConfig(Connection connection, String name, String value) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
			statement.setString(1, name);
			statement.setString(2, value);
			statement.execute();
		}
	}

}
