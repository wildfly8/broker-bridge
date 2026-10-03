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
