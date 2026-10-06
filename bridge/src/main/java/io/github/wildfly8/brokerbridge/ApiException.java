package io.github.wildfly8.brokerbridge;

/** A request the bridge refuses or can't complete; carries the HTTP status to return. */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;
	private final boolean retryable;
	/** Seconds for a {@code Retry-After} header, or null when the bridge has no wait to name. */
	private final Integer retryAfterSeconds;

	public ApiException(int status, String message) {
		this(status, message, false);
	}

	public ApiException(int status, String message, boolean retryable) {
		this(status, message, retryable, null);
	}

	public ApiException(int status, String message, boolean retryable, Integer retryAfterSeconds) {
		super(message);
		this.status = status;
		this.retryable = retryable;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public int status() {
		return status;
	}

	public boolean retryable() {
		return retryable;
	}

	public Integer retryAfterSeconds() {
		return retryAfterSeconds;
	}

	/**
	 * A wait the broker named in its own text ({@code "wait 15 seconds"}), between 1 and 600. Anything else, including a
	 * bare pacing violation, names no wait: the caller keeps its own back-off.
	 */
	public static Integer retryAfterSeconds(String text) {
		if (text == null) {
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(\\d+)\\s*(?:seconds|second|secs|sec)\\b").matcher(text);
		if (!m.find()) {
			return null;
		}
		int n;
		try {
			n = Integer.parseInt(m.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
		return n >= 1 && n <= 600 ? n : null;
	}
}
