package io.github.wildfly8.brokerbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ib.client.ComboLeg;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;

import io.github.wildfly8.brokerbridge.Model.Instrument;
import io.github.wildfly8.brokerbridge.Model.InstrumentDetails;
import io.github.wildfly8.brokerbridge.Model.Leg;

/** Neutral {@link Instrument} ⇄ IB {@link Contract}. */
final class Contracts {

	private static final Map<String, String> TO_IB = Map.of("STOCK", "STK", "OPTION", "OPT", "FUTURE", "FUT",
			"FUTURE_OPTION", "FOP", "INDEX", "IND", "FX", "CASH", "COMBO", "BAG");
	private static final Map<String, String> FROM_IB = Map.of("STK", "STOCK", "OPT", "OPTION", "FUT", "FUTURE",
			"FOP", "FUTURE_OPTION", "IND", "INDEX", "CASH", "FX", "BAG", "COMBO");

	private Contracts() {}

	static Contract toIb(Instrument in) {
		if (in == null) {
			throw new ApiException(400, "instrument is required");
		}
		String type = in.type() == null ? "STOCK" : in.type().toUpperCase();
		String secType = TO_IB.get(type);
		if (secType == null) {
			throw new ApiException(400, "unsupported instrument type: " + in.type());
		}
		Contract c = new Contract();
		c.secType(secType);
		if (in.brokerId() != null && !in.brokerId().isBlank()) {
			c.conid(conid(in.brokerId()));
		}
		c.symbol(in.symbol());
		c.exchange(in.exchange());
		c.primaryExch(in.primaryExchange());
		c.currency(in.currency());
		c.lastTradeDateOrContractMonth(in.expiry());
		if (in.strike() != null) {
			c.strike(in.strike());
		}
		if (in.right() != null) {
			c.right(switch (in.right().toUpperCase()) {
				case "CALL", "C" -> "C";
				case "PUT", "P" -> "P";
				default -> throw new ApiException(400, "unsupported right: " + in.right());
			});
		}
		c.multiplier(in.multiplier());
		if (in.legs() != null && !in.legs().isEmpty()) {
			List<ComboLeg> legs = new ArrayList<>();
			for (Leg leg : in.legs()) {
				ComboLeg l = new ComboLeg();
				l.conid(conid(leg.brokerId()));
				l.ratio(leg.ratio());
				l.action(side(leg.side()));
				l.exchange(leg.exchange() == null ? in.exchange() : leg.exchange());
				legs.add(l);
			}
			c.comboLegs(legs);
		}
		return c;
	}

	static Instrument fromIb(Contract c) {
		if (c == null) {
			return null;
		}
		String type = FROM_IB.getOrDefault(c.getSecType(), c.getSecType());
		boolean derivative = "OPTION".equals(type) || "FUTURE_OPTION".equals(type);
		List<Leg> legs = null;
		if (c.comboLegs() != null && !c.comboLegs().isEmpty()) {
			legs = new ArrayList<>();
			for (ComboLeg l : c.comboLegs()) {
				legs.add(new Leg(String.valueOf(l.conid()), l.ratio(), l.getAction(), blank(l.exchange())));
			}
		}
		return new Instrument(blank(c.symbol()), type, blank(c.exchange()), blank(c.primaryExch()), blank(c.currency()),
				blank(c.lastTradeDateOrContractMonth()), derivative ? right(c.getRight()) : null,
				derivative ? c.strike() : null, blank(c.multiplier()), c.conid() == 0 ? null : String.valueOf(c.conid()),
				legs);
	}

	static InstrumentDetails fromIb(ContractDetails d) {
		return new InstrumentDetails(fromIb(d.contract()), blank(d.marketName()), d.minTick(), d.priceMagnifier(),
				blank(d.orderTypes()), blank(d.validExchanges()));
	}

	static String side(String side) {
		if (side == null) {
			throw new ApiException(400, "side is required");
		}
		return switch (side.toUpperCase()) {
			case "BUY" -> "BUY";
			case "SELL" -> "SELL";
			default -> throw new ApiException(400, "unsupported side: " + side);
		};
	}

	private static int conid(String brokerId) {
		try {
			return Integer.parseInt(brokerId.trim());
		} catch (RuntimeException e) {
			throw new ApiException(400, "brokerId is not a broker contract id: " + brokerId);
		}
	}

	private static String right(String r) {
		if (r == null) {
			return null;
		}
		return switch (r) {
			case "C", "CALL" -> "CALL";
			case "P", "PUT" -> "PUT";
			default -> null;
		};
	}

	private static String blank(String s) {
		return s == null || s.isEmpty() ? null : s;
	}
}
