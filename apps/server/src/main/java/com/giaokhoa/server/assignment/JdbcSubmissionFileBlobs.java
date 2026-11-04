package com.giaokhoa.server.assignment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hiện thực của điểm nối nền tệp trên đúng hình dạng dữ kiện của lát L1: phiên tải lên và blob đọc từ hai
 * bảng `upload_session`/`file_blob` của nền đó, byte đọc từ vùng blob đã chốt của kho filesystem mà cổng lưu
 * trữ của nền tệp ghi (cấu hình `app.files.root` dùng chung). Khi nền tệp chưa hiện diện, bảng chưa tồn tại
 * thì mọi tra cứu trả rỗng — đường gắn tệp khép kín theo hướng từ chối, không suy đoán dữ kiện.
 */
@Component
public class JdbcSubmissionFileBlobs implements SubmissionFileBlobs {

	private final JdbcTemplate jdbc;

	private final Path blobDir;

	public JdbcSubmissionFileBlobs(JdbcTemplate jdbc, @Value("${app.files.root:./data/server-files}") String root) {
		this.jdbc = jdbc;
		this.blobDir = Path.of(root).resolve("blobs");
	}

	@Override
	public Optional<SessionInfo> findSession(UUID sessionId) {
		try {
			List<SessionInfo> rows = this.jdbc.query(
					"select s.status, s.blob_id, s.display_name, s.uploader_sub, b.status, b.content_type, "
							+ "b.size_bytes, b.created_at from upload_session s "
							+ "left join file_blob b on b.id = s.blob_id where s.id = ?",
					(rs, rowNum) -> new SessionInfo(sessionId, rs.getString(1),
							rs.getObject(2, UUID.class), rs.getString(5), rs.getString(3), rs.getString(6),
							rs.getObject(7, Long.class), rs.getString(4), toOffsetDateTime(rs.getTimestamp(8))),
					sessionId);
			return rows.stream().findFirst();
		}
		catch (BadSqlGrammarException ex) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<byte[]> readBlob(UUID blobId) {
		try {
			List<String> keys = this.jdbc.query("select storage_key from file_blob where id = ?",
					(rs, rowNum) -> rs.getString(1), blobId);
			if (keys.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(Files.readAllBytes(this.blobDir.resolve(keys.get(0)).normalize()));
		}
		catch (BadSqlGrammarException | IOException ex) {
			return Optional.empty();
		}
	}

	private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		return timestamp != null ? OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC) : null;
	}

}
