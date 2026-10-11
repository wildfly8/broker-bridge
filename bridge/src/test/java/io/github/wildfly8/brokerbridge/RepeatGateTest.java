package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RepeatGateTest {

	private final AtomicLong clock = new AtomicLong(1_000_000);
	private final RepeatGate gate = new RepeatGate(60_000, clock::get);

	@Test
	void theFirstOfAKindPassesAndTheRepeatsOfTheNextMinuteAreHeldBackAndCounted() {
		assertEquals(0, gate.admit(2188));
		for (int i = 0; i < 143; i++) {
			clock.addAndGet(300);
			assertEquals(-1, gate.admit(2188), "repeat " + i);
		}
		clock.addAndGet(60_000);
		assertEquals(143, gate.admit(2188), "the next one after the window says how many were held back");
		clock.addAndGet(60_000);
		assertEquals(0, gate.admit(2188), "and none were held back since");
	}

	@Test
	void eachKindHasItsOwnWindow() {
		assertEquals(0, gate.admit(2188));
		assertEquals(0, gate.admit(2150), "another code is not held back by 2188");
		assertEquals(-1, gate.admit(2188));
		assertEquals(-1, gate.admit(2150));
	}
}
