package io.github.wildfly8.brokerbridge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.wildfly8.brokerbridge.BridgeState.OrderRef;
import io.github.wildfly8.brokerbridge.Model.OrderStatusEvent;

/**
 * One reading of what the broker holds open and has completed, made at every connection to it: the bridge asks
 * instead of waiting to be told. IB documents that its order statuses are not guaranteed for every change, so an order that ended while the
 * bridge was away (cancelled, expired, rejected) never got its final status and, with its record in the journal, kept looking as if it worked.
 * <p>
 * The connection arms a reading with the orders the journal does not know to have ended ({@link EventLog#unfinishedOrders}) before it asks,
 * the callbacks note what the broker lists ({@link #open}, {@link #completed}) and when each list ends, and once they have ended
 * {@link #conclude} gives every armed order that the broker lists as neither open nor completed a final status. Orders placed after the
 * reading was armed are not in it: the connection is marked up, and so takes orders, only when the reading is over.
 */
final class OrderSync {

	private static final Logger log = LoggerFactory.getLogger(OrderSync.class);

	/** How long the connection waits for the broker to end its list of open orders before it goes on without concluding anything. */
	static final long OPEN_TIMEOUT_MS = 15_000;
	/** How long it waits for the list of completed orders after the open orders ended: that request is an addition, and an older Gateway may not answer it. */
	static final long COMPLETED_TIMEOUT_MS = 3_000;

	/** The status the bridge gives an armed order that the broker lists neither open nor completed ({@code Inactive}: received, no longer active). */
	static final String UNLISTED_STATUS = "Inactive";
	static final String UNLISTED_REASON = "not among IB's open orders when the bridge connected";
	static final String COMPLETED_REASON = "listed by IB as completed when the bridge connected";

	/** An order the broker lists as completed: its status, and its quantities when it says them. */
	record Completed(String status, Double filled, Double total) {}

	private final List<EventLog.OrderRecord> armed;
	private final Set<Long> open = ConcurrentHashMap.newKeySet();
	private final Map<Long, Completed> completed = new ConcurrentHashMap<>();
	private final CompletableFuture<Void> openEnded = new CompletableFuture<>();
	private final CompletableFuture<Void> completedEnded = new CompletableFuture<>();
	private final CompletableFuture<Void> aborted = new CompletableFuture<>();
	private volatile String notEnded;

	OrderSync(List<EventLog.OrderRecord> armed) {
		this.armed = List.copyOf(armed);
	}

	/** The broker lists the order as open. */
	void open(long clientOrderId) {
		open.add(clientOrderId);
	}

	/** The broker lists the order as completed, with the status it ended with. */
	void completed(long clientOrderId, String status, Double filled, Double total) {
		completed.put(clientOrderId, new Completed(status, filled, total));
	}

	void openEnd() {
		openEnded.complete(null);
	}

	void completedEnd() {
		completedEnded.complete(null);
	}

	/** The connection was lost: nothing is concluded and the wait ends. */
	void abort() {
		aborted.complete(null);
	}

	boolean openEnded() {
		return openEnded.isDone() && !aborted.isDone();
	}

	/**
	 * Waits until the broker has ended its list of open orders (at most {@link #OPEN_TIMEOUT_MS}) and then its list of completed ones (at
	 * most {@link #COMPLETED_TIMEOUT_MS} more), or the connection is lost. Returns whether the list of open orders ended.
	 */
	boolean await(long openTimeoutMs, long completedTimeoutMs) throws InterruptedException {
		notEnded = null;
		if (!waitFor(openEnded, openTimeoutMs)) {
			notEnded = openNotEnded(aborted.isDone(), openTimeoutMs);
			log.warn(notEnded);
			return false;
		}
		if (!waitFor(completedEnded, completedTimeoutMs)) {
			notEnded = completedNotEnded(aborted.isDone(), completedTimeoutMs);
			log.warn(notEnded);
		}
		return openEnded();
	}

	/** Why the last {@link #await} did not see a list end, in the words it logged; null when both lists ended. */
	String notEnded() {
		return notEnded;
	}

	/**
	 * The log line for a list of open orders that did not end. A lost connection ends the wait at once, and then it is not "within N ms": on 2026-10-14
	 * the Gateway dropped a handshake 160 ms after it began, and the line said 15000.
	 */
	static String openNotEnded(boolean connectionLost, long timeoutMs) {
		return connectionLost ? "The connection was lost before IB ended its list of open orders: no order is concluded to have ended"
				: "IB did not end its list of open orders within " + timeoutMs + " ms: no order is concluded to have ended";
	}

	/** The log line for a list of completed orders that did not end (the open orders' list had), as {@link #openNotEnded}. */
	static String completedNotEnded(boolean connectionLost, long timeoutMs) {
		return connectionLost ? "The connection was lost before IB ended its list of completed orders: no order is concluded to have ended"
				: "IB did not end its list of completed orders within " + timeoutMs + " ms: the orders it does not list as open are given the status " + UNLISTED_STATUS;
	}

	private boolean waitFor(CompletableFuture<Void> list, long timeoutMs) throws InterruptedException {
		try {
			CompletableFuture.anyOf(list, aborted).get(timeoutMs, TimeUnit.MILLISECONDS);
			return list.isDone() && !aborted.isDone();
		} catch (TimeoutException e) {
			return false;
		} catch (ExecutionException e) {
			return false;
		}
	}

	/**
	 * Gives every armed order that the broker lists neither open nor completed its final status, as an ordinary journaled {@code order-status}
	 * event: the status the broker gave it in its completed orders, and where it did not list it, {@link #UNLISTED_STATUS} with the reason. An
	 * order that reached a final status meanwhile (a live status, while the lists were being read) is left as it is.
	 *
	 * @return how many orders were concluded
	 */
	int conclude(BridgeState state, EventHub hub) {
		int concluded = 0;
		for (EventLog.OrderRecord rec : armed) {
			long id = rec.clientOrderId();
			if (open.contains(id) || hub.eventLog().isFinal(id)) {
				continue;
			}
			OrderRef ref = state.ordersByIbId.get(rec.brokerOrderId());
			String tag = ref != null ? ref.tag() : rec.tag();
			Completed c = completed.get(id);
			OrderStatusEvent event;
			if (c != null && c.status() != null && EventLog.isFinalStatus(c.status())) {
				Double remaining = c.total() != null && c.filled() != null ? c.total() - c.filled() : null;
				event = new OrderStatusEvent(id, c.status(), c.filled(), remaining, null, null, null, null, tag, COMPLETED_REASON, rec.instrument());
			} else {
				event = new OrderStatusEvent(id, UNLISTED_STATUS, null, null, null, null, null, null, tag, UNLISTED_REASON, rec.instrument());
			}
			hub.publishOrderEvent("order-status", event);
			log.info("Order {} (broker {}) is no longer open at IB: {} ({})", id, rec.brokerOrderId(), event.status(), event.whyHeld());
			concluded++;
		}
		log.info("IB's open orders read: {} open, {} completed listed, {} of the {} orders the bridge had not seen end are concluded ended",
				open.size(), completed.size(), concluded, armed.size());
		return concluded;
	}
}
