package io.github.wildfly8.brokerbridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import com.ib.client.Contract;
import com.ib.client.Order;

/** Records IB calls; optional hooks let a test answer them through the callbacks. */
class FakeIbClient implements IbClient {

	final List<String> calls = new CopyOnWriteArrayList<>();
	final List<Contract> contracts = new CopyOnWriteArrayList<>();
	final List<Order> orders = new CopyOnWriteArrayList<>();
	volatile boolean connected;
	volatile boolean refuseConnect;
	volatile int connectAttempts;
	/** Runs after a successful handshake, before {@link #connect} returns. Used to deliver error 502 in that window. */
	volatile Runnable onConnect = () -> {};
	volatile BiConsumer<Integer, Boolean> onMktData = (id, snapshot) -> {};
	volatile BiConsumer<Integer, Contract> onHistory = (id, c) -> {};
	volatile BiConsumer<Integer, Contract> onDetails = (id, c) -> {};
	volatile java.util.function.IntConsumer onExecutions = id -> {};

	@Override
	public boolean connect(String host, int port, int clientId) {
		connectAttempts++;
		calls.add("connect " + host + ":" + port + " " + clientId);
		connected = !refuseConnect;
		if (connected) {
			onConnect.run();
		}
		return connected;
	}

	@Override
	public void disconnect() {
		calls.add("disconnect");
		connected = false;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	@Override
	public int serverVersion() {
		return 222;
	}

	@Override
	public void reqMarketDataType(int type) {
		calls.add("marketDataType " + type);
	}

	@Override
	public void reqMktData(int reqId, Contract contract, String genericTicks, boolean snapshot) {
		calls.add("mktData " + reqId + " " + contract.symbol() + " [" + genericTicks + "] " + snapshot);
		contracts.add(contract);
		onMktData.accept(reqId, snapshot);
	}

	@Override
	public void cancelMktData(int reqId) {
		calls.add("cancelMktData " + reqId);
	}

	@Override
	public void reqHistoricalData(int reqId, Contract contract, String end, String duration, String barSize,
			String what, boolean regularHoursOnly) {
		calls.add("history " + reqId + " " + contract.symbol() + " " + end + "|" + duration + "|" + barSize + "|" + what
				+ "|" + regularHoursOnly);
		onHistory.accept(reqId, contract);
	}

	@Override
	public void cancelHistoricalData(int reqId) {
		calls.add("cancelHistory " + reqId);
	}

	@Override
	public void reqContractDetails(int reqId, Contract contract) {
		calls.add("details " + reqId + " " + contract.symbol());
		contracts.add(contract);
		onDetails.accept(reqId, contract);
	}

	@Override
	public void placeOrder(int ibOrderId, Contract contract, Order order) {
		calls.add("placeOrder " + ibOrderId + " " + order.getAction() + " " + order.totalQuantity() + " "
				+ order.getOrderType() + " " + order.lmtPrice());
		orders.add(order);
	}

	@Override
	public void cancelOrder(int ibOrderId) {
		calls.add("cancelOrder " + ibOrderId);
	}

	@Override
	public void reqIds() {
		calls.add("reqIds");
	}

	@Override
	public void reqExecutions(int reqId, int clientId) {
		calls.add("executions " + reqId + " " + clientId);
		onExecutions.accept(reqId);
	}

	long count(String prefix) {
		return calls.stream().filter(c -> c.startsWith(prefix)).count();
	}
}
