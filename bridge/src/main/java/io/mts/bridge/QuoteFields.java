package io.mts.bridge;

import java.util.Map;

/**
 * Neutral quote field names for the numeric field codes MTS uses. Delayed-data codes are folded into their
 * live equivalents so MTS sees the same field whatever the market data type.
 */
final class QuoteFields {

	static final int BID_SIZE = 0, BID = 1, ASK = 2, ASK_SIZE = 3, LAST = 4, LAST_SIZE = 5, HIGH = 6, LOW = 7,
			VOLUME = 8, CLOSE = 9, BID_OPTION = 10, ASK_OPTION = 11, LAST_OPTION = 12, MODEL_OPTION = 13, OPEN = 14,
			LAST_TIMESTAMP = 45;

	private static final Map<Integer, String> NAMES = Map.ofEntries(
			Map.entry(BID_SIZE, "BID_SIZE"), Map.entry(BID, "BID"), Map.entry(ASK, "ASK"),
			Map.entry(ASK_SIZE, "ASK_SIZE"), Map.entry(LAST, "LAST"), Map.entry(LAST_SIZE, "LAST_SIZE"),
			Map.entry(HIGH, "HIGH"), Map.entry(LOW, "LOW"), Map.entry(VOLUME, "VOLUME"), Map.entry(CLOSE, "CLOSE"),
			Map.entry(BID_OPTION, "BID_OPTION"), Map.entry(ASK_OPTION, "ASK_OPTION"),
			Map.entry(LAST_OPTION, "LAST_OPTION"), Map.entry(MODEL_OPTION, "MODEL_OPTION"), Map.entry(OPEN, "OPEN"),
			Map.entry(LAST_TIMESTAMP, "LAST_TIMESTAMP"));

	/** Delayed code → live code. */
	private static final Map<Integer, Integer> DELAYED = Map.ofEntries(
			Map.entry(66, BID), Map.entry(67, ASK), Map.entry(68, LAST), Map.entry(69, BID_SIZE),
			Map.entry(70, ASK_SIZE), Map.entry(71, LAST_SIZE), Map.entry(72, HIGH), Map.entry(73, LOW),
			Map.entry(74, VOLUME), Map.entry(75, CLOSE), Map.entry(76, OPEN), Map.entry(80, BID_OPTION),
			Map.entry(81, ASK_OPTION), Map.entry(82, LAST_OPTION), Map.entry(83, MODEL_OPTION),
			Map.entry(88, LAST_TIMESTAMP));

	private QuoteFields() {}

	static int normalize(int code) {
		return DELAYED.getOrDefault(code, code);
	}

	static String name(int code) {
		return NAMES.getOrDefault(code, "OTHER");
	}

	static boolean isOption(int code) {
		return code >= BID_OPTION && code <= MODEL_OPTION;
	}
}
