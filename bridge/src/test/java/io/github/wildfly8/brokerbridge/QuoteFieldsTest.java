package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The names the bridge reports in {@code field} for the broker's numeric codes (IB tick types). A client keeps its own table of the
 * same numbers and pins the same literal list in its own tests. Change the list on both sides, or neither.
 */
class QuoteFieldsTest {

	static final String BRIDGE_NAMES = "0=BID_SIZE,1=BID,2=ASK,3=ASK_SIZE,4=LAST,5=LAST_SIZE,6=HIGH,7=LOW,8=VOLUME,9=CLOSE,"
			+ "10=BID_OPTION,11=ASK_OPTION,12=LAST_OPTION,13=MODEL_OPTION,14=OPEN,45=LAST_TIMESTAMP";

	@Test
	void theNamesAreThePinnedList() {
		for (String pair : BRIDGE_NAMES.split(",")) {
			String[] p = pair.split("=");
			assertEquals(p[1], QuoteFields.name(Integer.parseInt(p[0])), "code " + p[0]);
		}
		assertEquals("OTHER", QuoteFields.name(21), "a code the bridge does not name is OTHER, and the client reads the code");
		assertEquals("OTHER", QuoteFields.name(999));
	}

	@Test
	void theDelayedCodesAreTold() {
		for (int delayed : new int[] { 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 80, 81, 82, 83, 88 }) {
			assertTrue(QuoteFields.isDelayed(delayed), "code " + delayed);
		}
		for (int live : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 45, 77, 99, 999 }) {
			assertFalse(QuoteFields.isDelayed(live), "code " + live);
		}
	}

	@Test
	void delayedCodesAreFoldedIntoTheirLiveFields() {
		assertEquals(QuoteFields.BID, QuoteFields.normalize(66));
		assertEquals(QuoteFields.LAST, QuoteFields.normalize(68));
		assertEquals(QuoteFields.LAST_TIMESTAMP, QuoteFields.normalize(88));
		assertEquals(QuoteFields.BID, QuoteFields.normalize(QuoteFields.BID), "a live code stays");
	}
}
