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
			assertFalse(log.truncatedAfter(a - 1), "nothing the journal kept is missing for a client that was at its start");
			assertTrue(log.truncatedAfter(0), "the journal began with the first run: a cursor from before it is older than what is kept");
		}
	}

	/** A client's cursor outlives the bridge's volume; the bridge must say that order events may be missing. */
	@Test
	void aNewOrLostJournalAnnouncesThatEarlierEventsMayBeMissing() throws Exception {
		long oldCursor;
		try (EventLog first = EventLog.open(dir, 100, 5_000L)) {
			oldCursor = first.nextId();
			first.keepOrderEvent(oldCursor, "fill", Map.of("clientOrderId", 7001, "fillId", "0001.01"));
		}
		// the volume is lost, the client keeps its cursor
		Files.delete(dir.resolve("journal.jsonl"));
		try (EventLog lost = EventLog.open(dir, 100, 9_000L)) {
			assertTrue(lost.truncatedAfter(oldCursor), "events after the client's cursor existed and are gone");
			assertTrue(lost.after(oldCursor).isEmpty());
			long mine = lost.nextId();
			assertFalse(lost.truncatedAfter(mine), "a cursor from this journal's own time is complete");
		}
		// a journal damaged to nothing is a lost one
		Files.writeString(dir.resolve("journal.jsonl"), "", StandardCharsets.UTF_8);
		try (EventLog empty = EventLog.open(dir, 100, 20_000L)) {
			assertTrue(empty.truncatedAfter(oldCursor));
		}
		// and the first start ever on an empty volume says the same of any older cursor, so that a restored volume or a copied cursor cannot pass as complete
		Path fresh = dir.resolve("fresh");
		try (EventLog first = EventLog.open(fresh, 100, 30_000L)) {
			assertTrue(first.truncatedAfter(oldCursor));
			assertFalse(first.truncatedAfter(first.nextId()));
		}
	}

	/** A restart that finds its journal keeps what the journal said and invents no truncation. */
	@Test
	void aJournalThatIsThereKeepsItsHorizonAcrossRestarts() throws Exception {
		long a;
		try (EventLog log = EventLog.open(dir, 100, 5_000L)) {
			a = log.nextId();
			log.keepOrderEvent(a, "order-status", Map.of("clientOrderId", 7001, "status", "Submitted"));
		}
		for (int restart = 0; restart < 3; restart++) {
			try (EventLog log = EventLog.open(dir, 100, 6_000L + restart)) {
				assertFalse(log.truncatedAfter(a - 1), "restart " + restart + ": the horizon is the one recorded, not this process's start");
				assertEquals(1, log.after(a - 1).size());
			}
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

	private static void order(EventLog log, long clientOrderId, String status) {
		log.recordOrder(new EventLog.OrderRecord(clientOrderId, (int) clientOrderId + 1000, "4", Instrument.stock("SPY", "SMART", "USD")));
		if (status != null) {
			log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", clientOrderId, "status", status));
		}
	}

	private static List<Long> orderIds(EventLog log) {
		return log.orders().stream().map(EventLog.OrderRecord::clientOrderId).toList();
	}

	/** A GTC order can work for weeks; the newest 10,000 records were all that survived. */
	@Test
	void theRecordOfAnOrderThatHasNotEndedIsKeptWhileEndedOnesGoOldestFirst() {
		EventLog log = EventLog.inMemory(1_000, 1_000_000L, 5, 20);
		order(log, 1, "Submitted");   // the old order that still works
		order(log, 2, "Filled");
		order(log, 3, "Cancelled");
		order(log, 4, null);          // placed, no status yet: not known to have ended
		order(log, 5, "Rejected");
		assertEquals(List.of(1L, 2L, 3L, 4L, 5L), orderIds(log));
		order(log, 6, "Filled");
		assertEquals(List.of(1L, 3L, 4L, 5L, 6L), orderIds(log), "the oldest ended one (2) went, not the open one (1)");
		order(log, 7, "Filled");
		order(log, 8, "Filled");
		assertEquals(List.of(1L, 4L, 6L, 7L, 8L), orderIds(log), "every record of an ended order is a candidate, oldest first; 1 and 4 have not ended");
		order(log, 9, "Submitted");
		order(log, 10, "Submitted");
		assertEquals(List.of(1L, 4L, 8L, 9L, 10L), orderIds(log), "the ended ones went as they came to be the oldest of their kind; 1 and 4 stay");
	}

	@Test
	void anOrderStatusAfterTheEndOfAnOrderRecordIsTheLatest() {
		EventLog log = EventLog.inMemory(1_000, 1_000_000L, 2, 20);
		order(log, 1, "Submitted");
		order(log, 2, "Submitted");
		log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", 1L, "status", "Filled"));
		order(log, 3, "Submitted");
		assertEquals(List.of(2L, 3L), orderIds(log), "1 ended after it was recorded: it is the one that goes");
	}

	@Test
	void orderRecordsOfOrdersThatNeverEndAreStillBounded() {
		EventLog log = EventLog.inMemory(1_000, 1_000_000L, 3, 6);
		for (long i = 1; i <= 10; i++) {
			order(log, i, "Submitted");
		}
		assertEquals(List.of(5L, 6L, 7L, 8L, 9L, 10L), orderIds(log), "a hard cap keeps the journal from growing for ever");
	}

	@Test
	void theRecordsOfOrdersThatHaveNotEndedSurviveARestartAndItsCompaction() throws Exception {
		try (EventLog log = EventLog.open(dir, 1_000, 5_000L, 4, 20)) {
			order(log, 1, "Submitted");
			for (long i = 2; i <= 9; i++) {
				order(log, i, "Filled");
			}
			assertEquals(List.of(1L, 7L, 8L, 9L), orderIds(log));
		}
		// the journal has every "o" line the run wrote; the load applies the same rule in the same order, so it comes out the same
		try (EventLog log = EventLog.open(dir, 1_000, 6_000L, 4, 20)) {
			assertEquals(List.of(1L, 7L, 8L, 9L), orderIds(log), "the open order's record is read back from the journal");
		}
		try (EventLog log = EventLog.open(dir, 1_000, 7_000L, 4, 20)) {
			assertEquals(List.of(1L, 7L, 8L, 9L), orderIds(log), "and again after the compaction of the second open");
		}
	}

	/** The orders that may still work, as far as the bridge knows, are the ones the reading of the broker's lists is armed with. */
	@Test
	void theUnfinishedOrdersAreThoseWhoseLatestStatusIsNotFinalAndThoseWithoutOneThatHaveAPlacementTime() {
		EventLog log = EventLog.inMemory(1000, 5_000L);
		Instrument spy = Instrument.stock("SPY", "SMART", "USD");
		log.recordOrder(new EventLog.OrderRecord(1, 11, "1", spy, "2026-10-13T12:00:00Z"));       // working
		log.recordOrder(new EventLog.OrderRecord(2, 12, "1", spy, "2026-10-13T12:00:00Z"));       // ended
		log.recordOrder(new EventLog.OrderRecord(3, 13, "1", spy, "2026-10-13T12:00:00Z"));       // no status yet, placed by this bridge
		log.recordOrder(new EventLog.OrderRecord(4, 14, "1", spy));                                // no status, from before the placement time was kept
		log.recordOrder(new EventLog.OrderRecord(5, 15, "1", spy));                                // old, but its status is still known
		log.recordOrder(new EventLog.OrderRecord(6, 16, "1", spy, "2026-10-13T12:00:00Z"));       // ended, its status replaced by a later one
		log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", 1, "status", "Submitted"));
		log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", 2, "status", "Filled"));
		log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", 5, "status", "PreSubmitted"));
		log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", 6, "status", "Cancelled"));
		assertEquals(List.of(1L, 3L, 5L), log.unfinishedOrders().stream().map(EventLog.OrderRecord::clientOrderId).toList());
		assertTrue(log.isFinal(2) && log.isFinal(6));
		assertFalse(log.isFinal(1), "a working status");
		assertFalse(log.isFinal(3), "no status");
		assertFalse(log.isFinal(99), "an order the log has never heard of");
	}

	private static List<Long> ids(List<EventLog.OrderRecord> orders) {
		return orders.stream().map(EventLog.OrderRecord::clientOrderId).toList();
	}

	/**
	 * An order with a record and no status among the kept events is unseen, and looked for at the next connection, only when it was placed after
	 * the oldest event the log has lost: before that, its statuses may be among the events that left the replay window and having none says
	 * nothing of it. (A count of the newest hundred gave the old ones their status a hundred at each connection instead of none.)
	 */
	@Test
	void anOrderWithoutAStatusIsUnfinishedOnlyWhenItWasPlacedAfterTheOldestEventTheLogLost() {
		EventLog log = EventLog.inMemory(5, 5_000L); // ids start at 5,000,000; five events are kept
		Instrument spy = Instrument.stock("SPY", "SMART", "USD");
		log.recordOrder(new EventLog.OrderRecord(1, 11, "1", spy, java.time.Instant.ofEpochMilli(5_000).toString()));
		assertEquals(List.of(1L), ids(log.unfinishedOrders()), "nothing has been lost yet");
		for (int i = 0; i < 7; i++) {
			log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", 9, "status", "Submitted"));
		}
		// the first two events left the window: everything up to the id 5,000,001 may be missing
		assertTrue(log.truncatedAfter(5_000_000L));
		assertFalse(log.truncatedAfter(5_000_001L));
		assertEquals(List.of(), ids(log.unfinishedOrders()), "order 1 was placed (5,000 ms: ids from 5,000,000) among the events that were lost");
		log.recordOrder(new EventLog.OrderRecord(2, 12, "1", spy, java.time.Instant.ofEpochMilli(5_002).toString()));
		assertEquals(List.of(2L), ids(log.unfinishedOrders()), "order 2 was placed after them: its statuses, if it had any, are all kept");
		// a long list of old orders without a status is not looked for, now or after another order is concluded
		for (int i = 0; i < 250; i++) {
			log.recordOrder(new EventLog.OrderRecord(100 + i, 1000 + i, "1", spy, java.time.Instant.ofEpochMilli(4_000).toString()));
		}
		assertEquals(List.of(2L), ids(log.unfinishedOrders()));
		log.keepOrderEvent(log.nextId(), "order-status", Map.of("clientOrderId", 2, "status", "Inactive"));
		assertEquals(List.of(), ids(log.unfinishedOrders()), "and concluding one does not bring the old ones in, as a count did");
	}

	/** The boundary is the id of the oldest lost event itself, and a placement time that cannot be read is no placement time. */
	@Test
	void theBoundaryIsTheOldestLostEventAndAPlacementTimeThatCannotBeReadCountsForNothing() throws Exception {
		String order = "{\"t\":\"o\",\"order\":{\"clientOrderId\":%d,\"brokerOrderId\":%d,\"tag\":\"4\","
				+ "\"instrument\":{\"symbol\":\"SPY\",\"type\":\"STOCK\"},\"placedAt\":\"%s\"}}\n";
		Files.writeString(dir.resolve("journal.jsonl"), "{\"t\":\"h\",\"id\":5000000}\n"
				+ String.format(order, 7001, 10, java.time.Instant.ofEpochMilli(5_000))        // ids from 5,000,000: the lost event is the first of its events
				+ String.format(order, 7002, 11, java.time.Instant.ofEpochMilli(5_001))        // ids from 5,001,000: all kept
				+ String.format(order, 7003, 12, "yesterday"), StandardCharsets.UTF_8);
		try (EventLog log = EventLog.open(dir, 100, 6_000L)) {
			assertEquals(List.of(7002L), ids(log.unfinishedOrders()));
		}
	}

	@Test
	void thePlacementTimeSurvivesARestartAndAJournalLineWithoutOneReadsAsNone() throws Exception {
		try (EventLog log = EventLog.open(dir, 100, 5_000L)) {
			log.recordOrder(new EventLog.OrderRecord(7001, 10, "4", Instrument.stock("SPY", "SMART", "USD"), "2026-10-13T12:00:00Z"));
		}
		// a record written before the placement time existed
		Files.writeString(dir.resolve("journal.jsonl"), Files.readString(dir.resolve("journal.jsonl")) + "{\"t\":\"o\",\"order\":{\"clientOrderId\":7002,"
				+ "\"brokerOrderId\":11,\"tag\":\"4\",\"instrument\":{\"symbol\":\"SPY\",\"type\":\"STOCK\"}}}\n", StandardCharsets.UTF_8);
		try (EventLog log = EventLog.open(dir, 100, 6_000L)) {
			assertEquals("2026-10-13T12:00:00Z", log.orders().get(0).placedAt());
			assertEquals(7002, log.orders().get(1).clientOrderId());
			assertEquals(null, log.orders().get(1).placedAt());
			assertEquals(List.of(7001L), log.unfinishedOrders().stream().map(EventLog.OrderRecord::clientOrderId).toList(),
					"the old record without a status is not concluded to have ended");
		}
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
