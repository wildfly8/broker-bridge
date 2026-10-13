package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.wildfly8.brokerbridge.OrderRefs.Parsed;

/** The order reference the bridge sends to the broker and reads back from it. */
class OrderRefsTest {

	@Test
	void theReferenceIsTheClientsOrderRefThenTheClientOrderIdThenTheTag() {
		assertEquals("INV#1791348607#3", OrderRefs.encode("INV", 1791348607L, "3"));
		assertEquals(new Parsed("INV", 1791348607L, "3"), OrderRefs.decode("INV#1791348607#3"));
	}

	@Test
	void anEmptyOrderRefAndAnEmptyTagAreNullAgain() {
		assertEquals("#7#", OrderRefs.encode(null, 7, null));
		assertEquals(new Parsed(null, 7L, null), OrderRefs.decode("#7#"));
		assertEquals(new Parsed(null, 7L, null), OrderRefs.decode(OrderRefs.encode("", 7, "")));
	}

	@Test
	void aHashInTheOrderRefOrTheTagIsKeptApart() {
		String ref = OrderRefs.encode("A#5", 7001, "t#1%23");
		assertEquals("A#5#7001#t%231%2523", ref);
		assertEquals(new Parsed("A#5", 7001L, "t#1%23"), OrderRefs.decode(ref));
		// a numeric tag does not turn into the client order id
		assertEquals(new Parsed("A#5", 7001L, "5001"), OrderRefs.decode(OrderRefs.encode("A#5", 7001, "5001")));
	}

	@Test
	void aNegativeIdAndTheBiggestIdSurvive() {
		assertEquals(new Parsed("INV", -3L, "1"), OrderRefs.decode(OrderRefs.encode("INV", -3, "1")));
		assertEquals(new Parsed("INV", 999_999_999_999_999_999L, "1"), OrderRefs.decode(OrderRefs.encode("INV", 999_999_999_999_999_999L, "1")));
	}

	@Test
	void aReferenceOfAnotherShapeIsNotTheBridgesOwn() {
		assertNull(OrderRefs.decode(null));
		assertNull(OrderRefs.decode(""));
		assertNull(OrderRefs.decode("INV"), "an order of an older bridge");
		assertNull(OrderRefs.decode("INV#12"));
		assertNull(OrderRefs.decode("INV#x#3"));
		assertNull(OrderRefs.decode("INV#12#x#y"), "the last two hashes do not enclose a number");
		assertEquals(new Parsed("INV#12", 3L, null), OrderRefs.decode("INV#12#3#"), "an order reference with a hash of its own, an empty tag");
		assertNull(OrderRefs.decode("INV#1234567890123456789#3"), "19 digits are no id of this bridge");
	}

	@Test
	void aReferenceOfAtMostOneHundredTwentyEightCharactersIsSentAndALongerOneIsRefused() {
		String tag = "t".repeat(OrderRefs.MAX_LENGTH - "INV#5#".length());
		assertEquals(OrderRefs.MAX_LENGTH, OrderRefs.encode("INV", 5, tag).length());
		ApiException refused = assertThrows(ApiException.class, () -> OrderRefs.encode("INV", 5, tag + "t"));
		assertEquals(400, refused.status());
	}
}
