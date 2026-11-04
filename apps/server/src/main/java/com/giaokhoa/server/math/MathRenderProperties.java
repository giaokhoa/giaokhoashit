package com.giaokhoa.server.math;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Toàn bộ giới hạn của đường render là cấu hình triển khai, không khoá cứng rải rác trong code: các giá trị
// mặc định ở đây là bộ số của phụ lục cấu hình trong đặc tả phần tệp và LaTeX, đổi được qua app.math.render.*
// mà không đổi hợp đồng dữ liệu.
@Component
@ConfigurationProperties("app.math.render")
public class MathRenderProperties {

	private String nodeExecutable = "node";

	private String workerScript = "math-worker/render.mjs";

	private int maxSourceLength = 2000;

	private int maxBlocksPerVersion = 50;

	private int maxBufferBytes = 5120;

	private int scale = 2;

	private int maxImageWidth = 4096;

	private int maxImageHeight = 4096;

	private int maxImagePixels = 20_000_000;

	private Duration taskTimeout = Duration.ofSeconds(10);

	private int taskMemoryMb = 512;

	private int maxConcurrentTasks = 4;

	public String getNodeExecutable() {
		return this.nodeExecutable;
	}

	public void setNodeExecutable(String nodeExecutable) {
		this.nodeExecutable = nodeExecutable;
	}

	public String getWorkerScript() {
		return this.workerScript;
	}

	public void setWorkerScript(String workerScript) {
		this.workerScript = workerScript;
	}

	public int getMaxSourceLength() {
		return this.maxSourceLength;
	}

	public void setMaxSourceLength(int maxSourceLength) {
		this.maxSourceLength = maxSourceLength;
	}

	public int getMaxBlocksPerVersion() {
		return this.maxBlocksPerVersion;
	}

	public void setMaxBlocksPerVersion(int maxBlocksPerVersion) {
		this.maxBlocksPerVersion = maxBlocksPerVersion;
	}

	public int getMaxBufferBytes() {
		return this.maxBufferBytes;
	}

	public void setMaxBufferBytes(int maxBufferBytes) {
		this.maxBufferBytes = maxBufferBytes;
	}

	public int getScale() {
		return this.scale;
	}

	public void setScale(int scale) {
		this.scale = scale;
	}

	public int getMaxImageWidth() {
		return this.maxImageWidth;
	}

	public void setMaxImageWidth(int maxImageWidth) {
		this.maxImageWidth = maxImageWidth;
	}

	public int getMaxImageHeight() {
		return this.maxImageHeight;
	}

	public void setMaxImageHeight(int maxImageHeight) {
		this.maxImageHeight = maxImageHeight;
	}

	public int getMaxImagePixels() {
		return this.maxImagePixels;
	}

	public void setMaxImagePixels(int maxImagePixels) {
		this.maxImagePixels = maxImagePixels;
	}

	public Duration getTaskTimeout() {
		return this.taskTimeout;
	}

	public void setTaskTimeout(Duration taskTimeout) {
		this.taskTimeout = taskTimeout;
	}

	public int getTaskMemoryMb() {
		return this.taskMemoryMb;
	}

	public void setTaskMemoryMb(int taskMemoryMb) {
		this.taskMemoryMb = taskMemoryMb;
	}

	public int getMaxConcurrentTasks() {
		return this.maxConcurrentTasks;
	}

	public void setMaxConcurrentTasks(int maxConcurrentTasks) {
		this.maxConcurrentTasks = maxConcurrentTasks;
	}

}
