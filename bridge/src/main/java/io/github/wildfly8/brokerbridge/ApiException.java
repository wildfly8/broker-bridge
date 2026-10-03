package io.github.wildfly8.brokerbridge;

/** A request the bridge refuses or can't complete; carries the HTTP status to return. */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;
	private final boolean retryable;

	public ApiException(int status, String message) {
		this(status, message, false);
	}

	public ApiException(int status, String message, boolean retryable) {
		super(message);
		this.status = status;
		this.retryable = retryable;
	}

	public int status() {
		return status;
	}

	public boolean retryable() {
		return retryable;
	}
}
