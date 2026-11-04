package com.giaokhoa.server.math;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// Hiện thực cổng render bằng worker Node trong thư mục math-worker: mỗi tác vụ là một tiến trình con riêng,
// nhận JSON qua stdin và trả JSON qua stdout. Tiến trình con là ranh giới cô lập thật của đường render: phía
// server áp trần số tác vụ đồng thời, timeout và trần bộ nhớ của tiến trình; worker không có đường mạng, lệnh
// hệ thống hay đọc tệp nào từ đầu vào LaTeX. stderr của worker bị bỏ đi toàn bộ vì không kênh phụ nào của
// worker được phép mang dữ kiện về phía người dùng.
@Component
public class NodeMathRenderWorker implements MathRenderer {

	private final MathRenderProperties properties;

	private final ObjectMapper objectMapper;

	private final Semaphore permits;

	private final ExecutorService ioExecutor = Executors.newCachedThreadPool((runnable) -> {
		Thread thread = new Thread(runnable, "math-render-io");
		thread.setDaemon(true);
		return thread;
	});

	public NodeMathRenderWorker(MathRenderProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.permits = new Semaphore(properties.getMaxConcurrentTasks());
	}

	@Override
	public RenderedImage render(String source, boolean display) {
		if (source == null || source.isEmpty() || source.length() > this.properties.getMaxSourceLength()) {
			throw new MathRenderException("SOURCE_TOO_LONG");
		}
		boolean acquired;
		try {
			acquired = this.permits.tryAcquire(this.properties.getTaskTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new MathRenderException("RENDER_INTERRUPTED");
		}
		if (!acquired) {
			throw new MathRenderException("RENDER_BUSY");
		}
		try {
			return runWorker(source, display);
		}
		finally {
			this.permits.release();
		}
	}

	private RenderedImage runWorker(String source, boolean display) {
		Process process;
		try {
			process = new ProcessBuilder(this.properties.getNodeExecutable(),
					"--max-old-space-size=" + this.properties.getTaskMemoryMb(), this.properties.getWorkerScript())
				.start();
		}
		catch (IOException ex) {
			throw new MathRenderException("RENDERER_UNAVAILABLE");
		}
		Future<byte[]> stdout = this.ioExecutor.submit(() -> readAll(process.getInputStream()));
		this.ioExecutor.submit(() -> readAll(process.getErrorStream()));
		try {
			try (OutputStream stdin = process.getOutputStream()) {
				stdin.write(taskJson(source, display).getBytes(StandardCharsets.UTF_8));
			}
			long timeoutMs = this.properties.getTaskTimeout().toMillis();
			if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new MathRenderException("RENDER_TIMEOUT");
			}
			JsonNode result = this.objectMapper.readTree(stdout.get(timeoutMs, TimeUnit.MILLISECONDS));
			if (result.hasNonNull("error")) {
				throw new MathRenderException(result.get("error").asString());
			}
			byte[] png = Base64.getDecoder().decode(result.get("pngBase64").asString());
			String rendererVersion = result.get("rendererVersion").asString() + "+"
					+ result.get("rasterizerVersion").asString();
			return new RenderedImage(png, result.get("width").asInt(), result.get("height").asInt(),
					result.get("baselineOffset").asInt(), rendererVersion, renderConfig(result));
		}
		catch (IOException | InterruptedException | ExecutionException | TimeoutException ex) {
			process.destroyForcibly();
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new MathRenderException("RENDER_FAILED");
		}
	}

	private String taskJson(String source, boolean display) {
		ObjectNode task = this.objectMapper.createObjectNode();
		task.put("source", source);
		task.put("display", display);
		ObjectNode config = task.putObject("config");
		config.put("maxSourceLength", this.properties.getMaxSourceLength());
		config.put("maxBufferBytes", this.properties.getMaxBufferBytes());
		config.put("scale", this.properties.getScale());
		config.put("maxImageWidth", this.properties.getMaxImageWidth());
		config.put("maxImageHeight", this.properties.getMaxImageHeight());
		config.put("maxImagePixels", this.properties.getMaxImagePixels());
		return task.toString();
	}

	// Bản ghi render giữ toàn bộ cấu hình hiệu lực đã sinh ảnh: giới hạn phía server cộng tập gói TeX và phiên
	// bản do chính worker báo về, để một ảnh luôn truy được đúng điều kiện đã sinh ra nó.
	private String renderConfig(JsonNode result) {
		ObjectNode config = this.objectMapper.createObjectNode();
		config.put("maxSourceLength", this.properties.getMaxSourceLength());
		config.put("maxBufferBytes", this.properties.getMaxBufferBytes());
		config.put("scale", this.properties.getScale());
		config.put("maxImageWidth", this.properties.getMaxImageWidth());
		config.put("maxImageHeight", this.properties.getMaxImageHeight());
		config.put("maxImagePixels", this.properties.getMaxImagePixels());
		config.put("taskTimeoutMs", this.properties.getTaskTimeout().toMillis());
		config.put("taskMemoryMb", this.properties.getTaskMemoryMb());
		ArrayNode packages = config.putArray("texPackages");
		result.get("packages").forEach((pkg) -> packages.add(pkg.asString()));
		return config.toString();
	}

	private byte[] readAll(InputStream stream) throws IOException {
		try (stream) {
			return stream.readAllBytes();
		}
	}

}
