package io.github.wildfly8.brokerbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The order reference the bridge sends to the broker: the client's own {@code orderRef}, then {@code #}, the client order id, {@code #} and the
 * tag ({@code INV#1791348607#3}). The broker returns it with the order whenever it reports it (open orders, completed orders, executions), so the
 * bridge recognises its own orders from the broker's data alone: after a restart that lost its journal, an order that works at the broker is
 * entered again with its client id and tag instead of being dropped as foreign.
 * <p>
 * The reference is read from the right: the last two {@code #} separate the client order id and the tag from the client's {@code orderRef},
 * which may contain {@code #} itself. The tag has {@code %} and {@code #} written as {@code %25} and {@code %23}. A reference of another
 * shape (an order of an older bridge, one placed in the broker's own application) is not the bridge's and is left alone.
 */
final class OrderRefs {

	/** The longest reference sent; the broker does not document a limit, and a longer one is refused before anything is claimed or sent. */
	static final int MAX_LENGTH = 128;

	private static final Pattern SHAPE = Pattern.compile("^(.*)#(-?\\d{1,18})#([^#]*)$", Pattern.DOTALL);

	/** What a reference says: the client's {@code orderRef} (null when it was empty), the client order id and the tag (null when it was empty). */
	record Parsed(String orderRef, long clientOrderId, String tag) {}

	private OrderRefs() {}

	static String encode(String orderRef, long clientOrderId, String tag) {
		String ref = (orderRef == null ? "" : orderRef) + "#" + clientOrderId + "#" + escape(tag);
		if (ref.length() > MAX_LENGTH) {
			throw new ApiException(400, "orderRef and tag are too long: the order reference sent to the broker (" + ref.length()
					+ " characters, with the client order id) may have at most " + MAX_LENGTH);
		}
		return ref;
	}

	/** The parts of a reference the bridge made, or null for any other. */
	static Parsed decode(String ref) {
		if (ref == null) {
			return null;
		}
		Matcher m = SHAPE.matcher(ref);
		if (!m.matches()) {
			return null;
		}
		String orderRef = m.group(1);
		String tag = unescape(m.group(3));
		return new Parsed(orderRef.isEmpty() ? null : orderRef, Long.parseLong(m.group(2)), tag.isEmpty() ? null : tag);
	}

	private static String escape(String tag) {
		return tag == null ? "" : tag.replace("%", "%25").replace("#", "%23");
	}

	private static String unescape(String tag) {
		return tag.replace("%23", "#").replace("%25", "%");
	}
}
