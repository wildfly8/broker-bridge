package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.github.wildfly8.brokerbridge.Model.Instrument;
import io.github.wildfly8.brokerbridge.Model.SubscriptionRequest;

class JsonTest {

	@Test
	void masksAccountIds() {
		assertEquals("DU*****67", Json.maskAccounts("DU1234567"));
		assertEquals("U1*****21", Json.maskAccounts("U12345621"));
		assertEquals("accounts DU*****67,DF****56 ok", Json.maskAccounts("accounts DU1234567,DF123456 ok"));
		assertEquals("order 1234567 at SMART", Json.maskAccounts("order 1234567 at SMART"));
		assertNull(Json.maskAccounts(null));
	}

	/** specs/002 FR-BRK-32: the event's own JSON has the instrument when the bridge holds it, and no field at all when it does not; the old shape still reads. */
	@Test
	void anOrderStatusHasTheInstrumentOnlyWhenTheBridgeHoldsIt() throws Exception {
		String with = Json.write(new Model.OrderStatusEvent(7001L, "Cancelled", 0.0, 1.0, null, null, "99", null, "4", null, Instrument.stock("SPY", "SMART", "USD")));
		assertEquals("SPY", Json.MAPPER.readTree(with).get("instrument").get("symbol").asText());
		String without = Json.write(new Model.OrderStatusEvent(7001L, "Cancelled", 0.0, 1.0, null, null, "99", null, "4", null));
		assertFalse(without.contains("instrument"), without);
		assertFalse(without.contains("null"), without);
		Model.OrderStatusEvent old = Json.MAPPER.readValue("{\"clientOrderId\":7001,\"status\":\"Filled\",\"tag\":\"4\"}", Model.OrderStatusEvent.class);
		assertNull(old.instrument());
		assertEquals("Filled", old.status());
	}

	@Test
	void omitsNullsAndIgnoresUnknownFields() throws Exception {
		String json = Json.write(Instrument.stock("SPY", "SMART", "USD"));
		assertFalse(json.contains("null"));
		SubscriptionRequest r = Json.MAPPER.readValue(
				"{\"instrument\":{\"symbol\":\"SPY\",\"type\":\"STOCK\",\"extra\":1},\"tag\":\"2\",\"future\":true}",
				SubscriptionRequest.class);
		assertEquals("SPY", r.instrument().symbol());
		assertEquals("2", r.tag());
	}
}
