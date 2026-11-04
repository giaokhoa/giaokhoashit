package com.giaokhoa.server.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hiện thực filesystem của cổng lưu trữ: thư mục gốc lấy từ cấu hình {@code app.files.root} (trên stack là một
 * volume riêng gắn cho service server, xem compose.yaml), vùng tạm và vùng blob đã chốt là hai thư mục con tách
 * biệt để khoá tạm không bao giờ là khoá của blob đang được dùng.
 */
@Component
public class FilesystemBlobStorage implements BlobStorage {

	private final Path stagingDir;

	private final Path blobDir;

	public FilesystemBlobStorage(@Value("${app.files.root:./data/server-files}") String root) {
		Path base = Path.of(root);
		this.stagingDir = base.resolve("staging");
		this.blobDir = base.resolve("blobs");
		try {
			Files.createDirectories(this.stagingDir);
			Files.createDirectories(this.blobDir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Không tạo được thư mục kho tệp tại " + base, ex);
		}
	}

	@Override
	public String stage(UUID sessionId, byte[] bytes) {
		String key = sessionId + ".part";
		try {
			Files.write(resolve(this.stagingDir, key), bytes);
			return key;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Không ghi được tệp vào vùng tạm", ex);
		}
	}

	@Override
	public byte[] readStaged(String stagingKey) {
		try {
			return Files.readAllBytes(resolve(this.stagingDir, stagingKey));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Không đọc được tệp ở vùng tạm", ex);
		}
	}

	@Override
	public void deleteStaged(String stagingKey) {
		if (stagingKey == null) {
			return;
		}
		try {
			Files.deleteIfExists(resolve(this.stagingDir, stagingKey));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Không xoá được tệp ở vùng tạm", ex);
		}
	}

	@Override
	public void promote(String stagingKey, String storageKey) {
		Path target = resolve(this.blobDir, storageKey);
		if (Files.exists(target)) {
			throw new IllegalStateException("Khoá lưu trữ đã tồn tại, blob là bất biến: " + storageKey);
		}
		try {
			Files.createDirectories(target.getParent());
			Files.move(resolve(this.stagingDir, stagingKey), target, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Không chốt được blob vào kho lưu trữ", ex);
		}
	}

	@Override
	public byte[] read(String storageKey) {
		try {
			return Files.readAllBytes(resolve(this.blobDir, storageKey));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Không đọc được blob trong kho lưu trữ", ex);
		}
	}

	@Override
	public int sweepStaging(Instant olderThan) {
		int removed = 0;
		try (Stream<Path> files = Files.list(this.stagingDir)) {
			for (Path file : files.toList()) {
				if (Files.getLastModifiedTime(file).toInstant().isBefore(olderThan)) {
					Files.deleteIfExists(file);
					removed++;
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Không dọn được vùng tạm của kho tệp", ex);
		}
		return removed;
	}

	// Khoá do server sinh chỉ gồm ký tự an toàn; bước resolve kiểm tra lại để một khoá lỗi không thoát khỏi thư
	// mục của kho.
	private static Path resolve(Path dir, String key) {
		Path path = dir.resolve(key).normalize();
		if (!path.startsWith(dir)) {
			throw new IllegalArgumentException("Khoá lưu trữ không hợp lệ: " + key);
		}
		return path;
	}

}
