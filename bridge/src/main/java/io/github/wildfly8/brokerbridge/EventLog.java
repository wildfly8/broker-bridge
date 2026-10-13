package io.github.wildfly8.brokerbridge;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.wildfly8.brokerbridge.Model.Instrument;

/**
 * Event ids, the order events kept for replay, and their optional on-disk journal (JSON lines).
 * <p>
 * Ids are one sequence for all events and keep increasing across restarts: they start at
 * max(highest journaled id + 1, now in ms × 1000), which is above any id an earlier run could have issued.
 * Only order events are kept and journaled; quotes are never replayed.
 */
final class EventLog implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(EventLog.class);

	private static final int MAX_ORDERS = 10_000;
	/**
	 * The most order records kept when none of the oldest has ended: the record of an order that has not ended is never dropped for age
	 * (a GTC order can work for weeks, and its events are dropped as foreign once its record is gone), only
	 * for this many.
	 */
	private static final int MAX_ORDERS_OPEN = 100_000;
	private static final int MAX_FILL_IDS = 50_000;
	/** The statuses after which an order does not work any more. */
	private static final Set<String> FINAL_STATUSES = Set.of("Filled", "Cancelled", "ApiCancelled", "Inactive", "Rejected");

	record Entry(long id, String event, String json) {}

	/**
	 * {@code placedAt}: when the bridge entered the order (ISO instant), or null for a record from before it was kept. Only the records that
	 * have one, or whose status the kept events still know, can be concluded to have ended without a status ({@link #unfinishedOrders}).
	 */
	record OrderRecord(long clientOrderId, int brokerOrderId, String tag, Instrument instrument, String placedAt) {
		OrderRecord(long clientOrderId, int brokerOrderId, String tag, Instrument instrument) {
			this(clientOrderId, brokerOrderId, tag, instrument, null);
		}
	}

	private final int maxKept;
	/** Order records kept before the oldest ended ones go; and the most that are kept when none has ended ({@link #MAX_ORDERS_OPEN}). */
	private final int maxOrders;
	private final int maxOrdersOpen;
	private final Path journal;
	private final ArrayDeque<Entry> kept = new ArrayDeque<>();
	private final Map<Long, OrderRecord> orders = new LinkedHashMap<>();
	/** The latest status of an order that the kept events (or the journal) have one for. */
	private final Map<Long, String> lastStatus = new java.util.HashMap<>();
	private final Set<String> fillIds = new LinkedHashSet<>();
	private long nextId;
	/** Order events with an id at or below this may be missing (evicted, or before this process: no journal, or a new or lost one). */
	private long horizon;
	private BufferedWriter writer;
	private FileChannel channel;

	private EventLog(int maxKept, Path journal, int maxOrders, int maxOrdersOpen) {
		this.maxKept = maxKept;
		this.journal = journal;
		this.maxOrders = maxOrders;
		this.maxOrdersOpen = maxOrdersOpen;
	}

	/** In-memory only: replay works across client reconnects but not across a bridge restart. */
	static EventLog inMemory(int maxKept, long nowMillis) {
		return inMemory(maxKept, nowMillis, MAX_ORDERS, MAX_ORDERS_OPEN);
	}

	/** Tests: other limits for the order records. */
	static EventLog inMemory(int maxKept, long nowMillis, int maxOrders, int maxOrdersOpen) {
		EventLog l = new EventLog(maxKept, null, maxOrders, maxOrdersOpen);
		l.nextId = nowMillis * 1000;
		l.horizon = l.nextId - 1;
		return l;
	}

	/** Loads (and compacts) {@code dir/journal.jsonl}, then appends to it. */
	static EventLog open(Path dir, int maxKept, long nowMillis) throws IOException {
		return open(dir, maxKept, nowMillis, MAX_ORDERS, MAX_ORDERS_OPEN);
	}

	/** Tests: other limits for the order records. */
	static EventLog open(Path dir, int maxKept, long nowMillis, int maxOrders, int maxOrdersOpen) throws IOException {
		Files.createDirectories(dir);
		EventLog l = new EventLog(maxKept, dir.resolve("journal.jsonl"), maxOrders, maxOrdersOpen);
		long maxId = 0;
		boolean headed = false;
		if (Files.exists(l.journal)) {
			for (String line : Files.readAllLines(l.journal, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode n;
				try {
					n = Json.MAPPER.readTree(line);
				} catch (IOException e) {
					log.warn("Skipping a damaged journal line ({} chars)", line.length());
					continue;
				}
				switch (n.path("t").asText()) {
					case "e" -> {
						Entry e = new Entry(n.path("id").asLong(), n.path("event").asText(), Json.write(n.path("data")));
						l.keep(e);
						maxId = Math.max(maxId, e.id());
						if ("fill".equals(e.event())) {
							l.rememberFill(n.path("data").path("fillId").asText(null));
						}
					}
					case "o" -> l.rememberOrder(Json.MAPPER.treeToValue(n.path("order"), OrderRecord.class));
					case "h" -> {
						headed = true;
						l.horizon = Math.max(l.horizon, n.path("id").asLong());
					}
					default -> { }
				}
			}
		}
		l.nextId = Math.max(maxId + 1, nowMillis * 1000);
		if (!headed) {
			// A journal that is new, or lost with its volume (or damaged to nothing), holds nothing from before this process: a client that
			// resumes with an older cursor must be told that order events may be missing, as it is when there is no journal at all. Every
			// journal this class has written starts with a header line, so a normal restart keeps the horizon the header recorded.
			l.horizon = Math.max(l.horizon, l.nextId - 1);
			log.warn("Journal {} is new or lost: order events from before id {} are not available to a client that resumes", l.journal, l.nextId);
		}
		l.compact();
		l.openWriter();
		log.info("Journal {}: {} order events kept, {} orders, next id {}", l.journal, l.kept.size(), l.orders.size(), l.nextId);
		return l;
	}

	synchronized long nextId() {
		return nextId++;
	}

	boolean durable() {
		return journal != null;
	}

	/** Keeps an order event for replay and journals it. */
	synchronized void keepOrderEvent(long id, String event, Object data) {
		String json = Json.write(data);
		keep(new Entry(id, event, json));
		ObjectNode line = Json.MAPPER.createObjectNode();
		line.put("t", "e");
		line.put("id", id);
		line.put("event", event);
		line.set("data", Json.MAPPER.valueToTree(data));
		append(line, true);
	}

	synchronized void recordOrder(OrderRecord order) {
		rememberOrder(order);
		ObjectNode line = Json.MAPPER.createObjectNode();
		line.put("t", "o");
		line.set("order", Json.MAPPER.valueToTree(order));
		append(line, true);
	}

	synchronized List<OrderRecord> orders() {
		return new ArrayList<>(orders.values());
	}

	/** Whether the latest status the kept events have for the order is a final one. */
	synchronized boolean isFinal(long clientOrderId) {
		return isFinalStatus(lastStatus.get(clientOrderId));
	}

	/** Whether the order does not work after this status. */
	static boolean isFinalStatus(String status) {
		return status != null && FINAL_STATUSES.contains(status);
	}

	/**
	 * The orders that may still work as far as the bridge knows: those whose latest status is not final, and those that have no
	 * status yet and were entered with a placement time. An order from before the placement time was kept and without a status among the kept
	 * events is old as likely as it is unseen, and is not listed.
	 */
	synchronized List<OrderRecord> unfinishedOrders() {
		List<OrderRecord> out = new ArrayList<>();
		for (OrderRecord o : orders.values()) {
			String status = lastStatus.get(o.clientOrderId());
			if (status != null ? !FINAL_STATUSES.contains(status) : o.placedAt() != null) {
				out.add(o);
			}
		}
		return out;
	}

	/** True the first time a fill id is seen (in this run or in the journal). */
	synchronized boolean firstFill(String fillId) {
		if (fillId == null) {
			return true;
		}
		return rememberFill(fillId);
	}

	/** Kept order events with an id above {@code after}, oldest first. */
	synchronized List<Entry> after(long after) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : kept) {
			if (e.id() > after) {
				out.add(e);
			}
		}
		return out;
	}

	/** Whether order events after {@code after} may have been lost (older than what is kept). */
	synchronized boolean truncatedAfter(long after) {
		return after < horizon;
	}

	synchronized long oldestKept() {
		return kept.isEmpty() ? nextId : kept.peekFirst().id();
	}

	private void keep(Entry e) {
		if ("order-status".equals(e.event())) {
			try {
				JsonNode n = Json.MAPPER.readTree(e.json());
				if (n.hasNonNull("clientOrderId") && n.hasNonNull("status")) {
					lastStatus.put(n.path("clientOrderId").asLong(), n.path("status").asText());
				}
			} catch (IOException ex) {
				log.warn("An order status event of {} chars could not be read for the order records: {}", e.json().length(), ex.toString());
			}
		}
		if (lastStatus.size() > 2 * maxOrdersOpen) {
			lastStatus.keySet().retainAll(orders.keySet());
		}
		kept.addLast(e);
		while (kept.size() > maxKept) {
			horizon = Math.max(horizon, kept.removeFirst().id());
		}
	}

	private void rememberOrder(OrderRecord o) {
		orders.remove(o.clientOrderId());
		orders.put(o.clientOrderId(), o);
		if (orders.size() <= maxOrders) {
			return;
		}
		// the oldest records go, those of orders that ended first and only those: an order whose last status is not final (or that has
		// none yet) keeps its record, up to MAX_ORDERS_OPEN
		java.util.Iterator<Map.Entry<Long, OrderRecord>> it = orders.entrySet().iterator();
		while (orders.size() > maxOrders && it.hasNext()) {
			Long id = it.next().getKey();
			String status = lastStatus.get(id);
			if (status != null && FINAL_STATUSES.contains(status)) {
				it.remove();
				lastStatus.remove(id);
			}
		}
		it = orders.entrySet().iterator();
		while (orders.size() > maxOrdersOpen && it.hasNext()) {
			lastStatus.remove(it.next().getKey());
			it.remove();
		}
	}

	private boolean rememberFill(String fillId) {
		if (fillId == null || !fillIds.add(fillId)) {
			return false;
		}
		while (fillIds.size() > MAX_FILL_IDS) {
			fillIds.remove(fillIds.iterator().next());
		}
		return true;
	}

	/** Rewrites the journal with only what is kept. */
	private void compact() throws IOException {
		Path tmp = journal.resolveSibling(journal.getFileName() + ".tmp");
		try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			ObjectNode h = Json.MAPPER.createObjectNode();
			h.put("t", "h");
			h.put("id", horizon);
			w.write(Json.write(h));
			w.newLine();
			for (OrderRecord o : orders.values()) {
				ObjectNode line = Json.MAPPER.createObjectNode();
				line.put("t", "o");
				line.set("order", Json.MAPPER.valueToTree(o));
				w.write(Json.write(line));
				w.newLine();
			}
			for (Entry e : kept) {
				ObjectNode line = Json.MAPPER.createObjectNode();
				line.put("t", "e");
				line.put("id", e.id());
				line.put("event", e.event());
				line.set("data", Json.MAPPER.readTree(e.json()));
				w.write(Json.write(line));
				w.newLine();
			}
		}
		Files.move(tmp, journal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private void openWriter() throws IOException {
		channel = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
		writer = new BufferedWriter(new java.io.OutputStreamWriter(java.nio.channels.Channels.newOutputStream(channel),
				StandardCharsets.UTF_8));
	}

	private void append(ObjectNode line, boolean sync) {
		if (writer == null) {
			return;
		}
		try {
			writer.write(Json.write(line));
			writer.newLine();
			writer.flush();
			if (sync) {
				channel.force(false);
			}
		} catch (IOException e) {
			log.error("Journal write failed: {}", e.toString());
		}
	}

	@Override
	public synchronized void close() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				log.warn("Journal close failed: {}", e.toString());
			}
			writer = null;
		}
	}
}
