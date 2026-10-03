package io.mts.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ib.client.Order;

import io.mts.bridge.Model.Instrument;
import io.mts.bridge.Model.OrderRequest;

class OrdersTest {

	private static OrderRequest order(String side, String type, Double limit, Double stop, String tif) {
		return new OrderRequest(7001L, 4, Instrument.stock("SPY", "SMART", "USD"), side, 100.0, type, limit, stop, tif,
				true, "INV", null);
	}

	@Test
	void limitDay() {
		Order o = Orders.toIb(order("BUY", "LIMIT", 500.0, null, null), 42);
		assertEquals(42, o.orderId());
		assertEquals("BUY", o.getAction());
		assertEquals("100", o.totalQuantity().toString());
		assertEquals("LMT", o.getOrderType());
		assertEquals(500.0, o.lmtPrice());
		assertEquals("DAY", o.getTif());
		assertTrue(o.allOrNone());
		assertEquals("INV", o.orderRef());
		assertTrue(o.transmit());
	}

	@Test
	void marketIoc() {
		Order o = Orders.toIb(order("sell", "MARKET", null, null, "IOC"), 1);
		assertEquals("SELL", o.getAction());
		assertEquals("MKT", o.getOrderType());
		assertEquals("IOC", o.getTif());
	}

	@Test
	void stopGtc() {
		Order o = Orders.toIb(order("SELL", "STOP", null, 490.0, "GTC"), 1);
		assertEquals("STP", o.getOrderType());
		assertEquals(490.0, o.auxPrice());
		assertEquals("GTC", o.getTif());
		assertFalse(Orders.toIb(order("SELL", "STOP_LIMIT", 489.0, 490.0, "DAY"), 1).getOrderType().isEmpty());
	}

	@Test
	void rejectsIncompleteOrders() {
		assertEquals(400, assertThrows(ApiException.class, () -> Orders.toIb(order("BUY", "LIMIT", null, null, null), 1)).status());
		assertEquals(400, assertThrows(ApiException.class, () -> Orders.toIb(order("BUY", "STOP", null, null, null), 1)).status());
		assertEquals(400, assertThrows(ApiException.class, () -> Orders.toIb(order("HOLD", "MARKET", null, null, null), 1)).status());
		assertEquals(400, assertThrows(ApiException.class, () -> Orders.toIb(order("BUY", "PEG", null, null, null), 1)).status());
		assertEquals(400, assertThrows(ApiException.class, () -> Orders.toIb(order("BUY", "MARKET", null, null, "FOK"), 1)).status());
		OrderRequest zero = new OrderRequest(1L, 1, Instrument.stock("SPY", "SMART", "USD"), "BUY", 0.0, "MARKET",
				null, null, null, null, null, null);
		assertEquals(400, assertThrows(ApiException.class, () -> Orders.toIb(zero, 1)).status());
	}
}
