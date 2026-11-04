package com.giaokhoa.server.grading;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Bảng tạm của khối giao/làm bài chỉ tồn tại trong database test của khối này: khối chấm đọc và chuyển trạng
 * thái qua các bảng đó bằng điểm nối dữ kiện nhưng không sở hữu migration của chúng, nên bộ test tự dựng đúng
 * hình dạng đã đặc tả để chạy trên đường thật. Hai bảng của khối tổ chức dạy học (teaching_class, class_member)
 * đã có migration thật V20/V21 trong cây nên không dựng tạm nữa. Khi khối giao/làm bài hợp nhất, migration thật
 * của nó (V50–V59, đứng trước V60) tạo bảng trước và phần dựng tạm còn lại này phải được gỡ.
 */
final class SeamTables {

	private static final String[] DDL = {
			"""
			create table if not exists exam_assignment (
			    id uuid primary key,
			    partition_key varchar(255) not null,
			    class_id uuid not null,
			    exam_resource_id uuid not null,
			    exam_version_id uuid not null,
			    assigned_by_sub varchar(255) not null,
			    opens_at timestamp with time zone,
			    due_at timestamp with time zone,
			    created_at timestamp with time zone not null default current_timestamp,
			    updated_at timestamp with time zone not null default current_timestamp,
			    unique (id, partition_key)
			)
			""",
			"""
			create table if not exists exam_attempt (
			    id uuid primary key,
			    partition_key varchar(255) not null,
			    assignment_id uuid not null,
			    student_sub varchar(255) not null,
			    status varchar(32) not null,
			    started_at timestamp with time zone,
			    submitted_at timestamp with time zone,
			    submit_mode varchar(16),
			    created_at timestamp with time zone not null default current_timestamp,
			    updated_at timestamp with time zone not null default current_timestamp,
			    unique (id, partition_key),
			    unique (assignment_id, student_sub)
			)
			""",
			"""
			create table if not exists attempt_answer (
			    id uuid primary key,
			    partition_key varchar(255) not null,
			    attempt_id uuid not null,
			    exam_item_id uuid not null,
			    answer text,
			    created_at timestamp with time zone not null default current_timestamp,
			    updated_at timestamp with time zone not null default current_timestamp,
			    unique (attempt_id, exam_item_id)
			)
			""" };

	private SeamTables() {
	}

	static void create(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			for (String sql : DDL) {
				statement.execute(sql);
			}
		}
	}

}
