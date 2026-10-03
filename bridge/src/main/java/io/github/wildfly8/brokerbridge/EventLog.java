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
	private static final int MAX_FILL_IDS = 50_000;

	record Entry(long id, String event, String json) {}

	record OrderRecord(long clientOrderId, int brokerOrderId, String tag, Instrument instrument) {}

	private final int maxKept;
	private final Path journal;
	private final ArrayDeque<Entry> kept = new ArrayDeque<>();
	private final Map<Long, OrderRecord> orders = new LinkedHashMap<>();
	private final Set<String> fillIds = new LinkedHashSet<>();
	private long nextId;
	/** Order events with an id at or below this may be missing (evicted, or before this process without a journal). */
	private long horizon;
	private BufferedWriter writer;
	private FileChannel channel;

	private EventLog(int maxKept, Path journal) {
		this.maxKept = maxKept;
		this.journal = journal;
	}

	/** In-memory only: replay works across client reconnects but not across a bridge restart. */
	static EventLog inMemory(int maxKept, long nowMillis) {
		EventLog l = new EventLog(maxKept, null);
		l.nextId = nowMillis * 1000;
		l.horizon = l.nextId - 1;
		return l;
	}

	/** Loads (and compacts) {@code dir/journal.jsonl}, then appends to it. */
	static EventLog open(Path dir, int maxKept, long nowMillis) throws IOException {
		Files.createDirectories(dir);
		EventLog l = new EventLog(maxKept, dir.resolve("journal.jsonl"));
		long maxId = 0;
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
					case "h" -> l.horizon = Math.max(l.horizon, n.path("id").asLong());
					default -> { }
				}
			}
		}
		l.nextId = Math.max(maxId + 1, nowMillis * 1000);
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
		kept.addLast(e);
		while (kept.size() > maxKept) {
			horizon = Math.max(horizon, kept.removeFirst().id());
		}
	}

	private void rememberOrder(OrderRecord o) {
		orders.remove(o.clientOrderId());
		orders.put(o.clientOrderId(), o);
		while (orders.size() > MAX_ORDERS) {
			orders.remove(orders.keySet().iterator().next());
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
