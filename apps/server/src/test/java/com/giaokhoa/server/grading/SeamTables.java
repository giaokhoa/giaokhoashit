package com.giaokhoa.server.grading;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Bảng tạm của các khối kề (tổ chức dạy học và giao/làm bài) chỉ tồn tại trong database test của khối này: khối
 * chấm đọc và chuyển trạng thái qua các bảng đó bằng điểm nối dữ kiện nhưng không sở hữu migration của chúng,
 * nên bộ test tự dựng đúng hình dạng đã đặc tả để chạy trên đường thật. Khi các khối kề hợp nhất, migration thật
 * của chúng (V20–V29 và V50–V59, đều đứng trước V60) tạo bảng trước và phần dựng tạm này phải được gỡ.
 */
final class SeamTables {

	private static final String[] DDL = {
			"""
			create table if not exists teaching_class (
			    id uuid primary key,
			    partition_key varchar(255) not null,
			    course_id uuid not null,
			    name varchar(255) not null,
			    teacher_sub varchar(255),
			    created_by_sub varchar(255) not null,
			    created_at timestamp with time zone not null default current_timestamp,
			    updated_at timestamp with time zone not null default current_timestamp,
			    unique (id, partition_key)
			)
			""",
			"""
			create table if not exists class_member (
			    id uuid primary key,
			    partition_key varchar(255) not null,
			    class_id uuid not null,
			    student_sub varchar(255) not null,
			    enrolled_by_sub varchar(255) not null,
			    removed_at timestamp with time zone,
			    created_at timestamp with time zone not null default current_timestamp,
			    updated_at timestamp with time zone not null default current_timestamp,
			    unique (class_id, partition_key, student_sub)
			)
			""",
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
