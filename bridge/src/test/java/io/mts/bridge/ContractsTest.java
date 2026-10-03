package io.mts.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ib.client.ComboLeg;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;

import io.mts.bridge.Model.Instrument;
import io.mts.bridge.Model.InstrumentDetails;
import io.mts.bridge.Model.Leg;

class ContractsTest {

	@Test
	void stock() {
		Contract c = Contracts.toIb(Instrument.stock("SPY", "SMART", "USD"));
		assertEquals("STK", c.getSecType());
		assertEquals("SPY", c.symbol());
		assertEquals("SMART", c.exchange());
		assertEquals("USD", c.currency());
		assertEquals(0, c.conid());

		Instrument back = Contracts.fromIb(c);
		assertEquals("STOCK", back.type());
		assertNull(back.strike());
		assertNull(back.right());
		assertNull(back.brokerId());
	}

	@Test
	void option() {
		Instrument in = new Instrument("SPY", "OPTION", "SMART", null, "USD", "20261218", "CALL", 500.0, "100", "123",
				null);
		Contract c = Contracts.toIb(in);
		assertEquals("OPT", c.getSecType());
		assertEquals("C", c.getRight());
		assertEquals(500.0, c.strike());
		assertEquals("20261218", c.lastTradeDateOrContractMonth());
		assertEquals("100", c.multiplier());
		assertEquals(123, c.conid());
		assertEquals(in, Contracts.fromIb(c));
	}

	@Test
	void combo() {
		Instrument in = new Instrument("SPY", "COMBO", "SMART", null, "USD", null, null, null, null, null,
				List.of(new Leg("111", 1, "BUY", "SMART"), new Leg("222", 2, "SELL", null)));
		Contract c = Contracts.toIb(in);
		assertEquals("BAG", c.getSecType());
		assertEquals(2, c.comboLegs().size());
		ComboLeg second = c.comboLegs().get(1);
		assertEquals(222, second.conid());
		assertEquals(2, second.ratio());
		assertEquals("SELL", second.getAction());
		assertEquals("SMART", second.exchange());
		Instrument back = Contracts.fromIb(c);
		assertEquals("COMBO", back.type());
		assertEquals(new Leg("222", 2, "SELL", "SMART"), back.legs().get(1));
	}

	@Test
	void details() {
		ContractDetails d = new ContractDetails();
		Contract c = Contracts.toIb(Instrument.stock("SPY", "SMART", "USD"));
		c.conid(756733);
		d.contract(c);
		d.marketName("SPY");
		d.minTick(0.01);
		d.priceMagnifier(1);
		d.orderTypes("LMT,MKT");
		d.validExchanges("SMART,ARCA");
		InstrumentDetails n = Contracts.fromIb(d);
		assertEquals("756733", n.instrument().brokerId());
		assertEquals("SPY", n.marketName());
		assertEquals(0.01, n.minTick());
		assertEquals(1, n.priceMagnifier());
		assertEquals("SMART,ARCA", n.validExchanges());
	}

	@Test
	void rejectsBadInput() {
		assertEquals(400, assertThrows(ApiException.class, () -> Contracts.toIb(null)).status());
		assertEquals(400, assertThrows(ApiException.class, () -> Contracts.toIb(
				new Instrument("X", "BOND", null, null, null, null, null, null, null, null, null))).status());
		assertEquals(400, assertThrows(ApiException.class, () -> Contracts.toIb(
				new Instrument("X", "OPTION", null, null, null, null, "UP", null, null, null, null))).status());
		assertEquals(400, assertThrows(ApiException.class, () -> Contracts.toIb(
				new Instrument("X", "STOCK", null, null, null, null, null, null, null, "abc", null))).status());
	}
}
