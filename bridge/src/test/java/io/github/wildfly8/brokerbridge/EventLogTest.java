package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.wildfly8.brokerbridge.Model.Instrument;

class EventLogTest {

	@TempDir
	Path dir;

	@Test
	void idsKeepIncreasingAcrossRestartsWithoutAJournal() {
		EventLog first = EventLog.inMemory(10, 1_000_000L);
		long last = 0;
		for (int i = 0; i < 999; i++) {
			last = first.nextId();
		}
		EventLog second = EventLog.inMemory(10, 1_000_001L);
		assertTrue(second.nextId() > last);
		assertTrue(second.truncatedAfter(last), "events before this process may be missing");
		assertFalse(second.truncatedAfter(second.nextId()));
	}

	@Test
	void journalSurvivesRestartAndIdsStayAboveIt() throws Exception {
		long a;
		long b;
		try (EventLog log = EventLog.open(dir, 100, 5_000L)) {
			log.recordOrder(new EventLog.OrderRecord(7001, 10, "4", Instrument.stock("SPY", "SMART", "USD")));
			a = log.nextId();
			log.keepOrderEvent(a, "order-status", Map.of("clientOrderId", 7001, "status", "Submitted"));
			log.nextId(); // a quote, never journaled
			b = log.nextId();
			assertTrue(log.firstFill("0001.01"));
			log.keepOrderEvent(b, "fill", Map.of("clientOrderId", 7001, "fillId", "0001.01"));
		}
		// clock earlier than before: ids must still continue above the journal
		try (EventLog log = EventLog.open(dir, 100, 1L)) {
			assertTrue(log.nextId() > b);
			List<EventLog.Entry> after = log.after(a);
			assertEquals(1, after.size());
			assertEquals(b, after.get(0).id());
			assertEquals("fill", after.get(0).event());
			assertTrue(after.get(0).json().contains("\"fillId\":\"0001.01\""));
			assertEquals(2, log.after(0).size());
			assertFalse(log.firstFill("0001.01"), "fill ids come back from the journal");
			assertEquals(1, log.orders().size());
			assertEquals(10, log.orders().get(0).brokerOrderId());
			assertEquals("4", log.orders().get(0).tag());
			assertFalse(log.truncatedAfter(0));
		}
	}

	@Test
	void retentionTruncatesOldEventsAndCompactsTheJournal() throws Exception {
		long[] ids = new long[3];
		try (EventLog log = EventLog.open(dir, 2, 5_000L)) {
			for (int i = 0; i < 3; i++) {
				ids[i] = log.nextId();
				log.keepOrderEvent(ids[i], "order-status", Map.of("n", i));
			}
			assertEquals(2, log.after(0).size());
			assertTrue(log.truncatedAfter(ids[0] - 1));
			assertFalse(log.truncatedAfter(ids[0]), "after the evicted one, nothing is missing");
			assertEquals(ids[1], log.oldestKept());
		}
		try (EventLog log = EventLog.open(dir, 2, 5_000L)) {
			assertEquals(2, log.after(0).size());
			assertTrue(log.truncatedAfter(ids[0] - 1), "horizon survives compaction");
		}
		long lines = Files.readAllLines(dir.resolve("journal.jsonl")).stream().filter(l -> l.contains("\"t\":\"e\"")).count();
		assertEquals(2, lines, "compacted on open");
	}

	@Test
	void damagedLinesAreSkipped() throws Exception {
		Files.writeString(dir.resolve("journal.jsonl"),
				"{\"t\":\"e\",\"id\":42,\"event\":\"fill\",\"data\":{\"fillId\":\"x\"}}\n{\"t\":\"e\",\"id\":4", StandardCharsets.UTF_8);
		try (EventLog log = EventLog.open(dir, 10, 0L)) {
			assertEquals(1, log.after(0).size());
			assertEquals(43, log.nextId());
		}
	}
}
