package io.github.wildfly8.brokerbridge;

import java.math.BigDecimal;

import com.ib.client.Decimal;
import com.ib.client.Order;

import io.github.wildfly8.brokerbridge.Model.OrderRequest;

/** Neutral {@link OrderRequest} → IB {@link Order}. */
final class Orders {

	private Orders() {}

	static Order toIb(OrderRequest r, int ibOrderId) {
		if (r.quantity() == null || r.quantity() <= 0) {
			throw new ApiException(400, "quantity must be positive");
		}
		Order o = new Order();
		o.orderId(ibOrderId);
		o.action(Contracts.side(r.side()));
		o.totalQuantity(Decimal.get(BigDecimal.valueOf(r.quantity())));
		String type = r.type() == null ? "LIMIT" : r.type().toUpperCase();
		switch (type) {
			case "LIMIT" -> {
				o.orderType("LMT");
				o.lmtPrice(required(r.limitPrice(), "limitPrice"));
			}
			case "MARKET" -> o.orderType("MKT");
			case "STOP" -> {
				o.orderType("STP");
				o.auxPrice(required(r.stopPrice(), "stopPrice"));
			}
			case "LIMIT_IF_TOUCHED" -> {
				o.orderType("LIT");
				o.lmtPrice(required(r.limitPrice(), "limitPrice"));
				o.auxPrice(required(r.stopPrice(), "stopPrice"));
			}
			case "STOP_LIMIT" -> {
				o.orderType("STP LMT");
				o.lmtPrice(required(r.limitPrice(), "limitPrice"));
				o.auxPrice(required(r.stopPrice(), "stopPrice"));
			}
			default -> throw new ApiException(400, "unsupported order type: " + r.type());
		}
		String tif = r.timeInForce() == null ? "DAY" : r.timeInForce().toUpperCase();
		if (!tif.equals("DAY") && !tif.equals("GTC") && !tif.equals("IOC")) {
			throw new ApiException(400, "unsupported timeInForce: " + r.timeInForce());
		}
		o.tif(tif);
		o.allOrNone(Boolean.TRUE.equals(r.allOrNone()));
		o.orderRef(r.orderRef());
		o.transmit(true);
		return o;
	}

	private static double required(Double v, String name) {
		if (v == null) {
			throw new ApiException(400, name + " is required");
		}
		return v;
	}
}
