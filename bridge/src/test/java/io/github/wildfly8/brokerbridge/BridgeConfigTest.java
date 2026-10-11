package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeConfigTest {

	@Test
	void defaultsAreLocalPaperAndOrdersOff() {
		BridgeConfig c = BridgeConfig.fromEnv(Map.of());
		assertEquals("127.0.0.1", c.ibHost());
		assertEquals(4002, c.ibPort());
		assertEquals(11, c.ibClientId());
		assertEquals("127.0.0.1", c.bind());
		assertEquals(8090, c.port());
		assertFalse(c.ordersEnabled());
		assertEquals(1, c.marketDataType());
		assertEquals(null, c.dataDir());
		assertEquals(100_000, c.replayMax());
		assertTrue(c.watchdog(), "the watchdog is on unless BRIDGE_WATCHDOG=off");
	}

	@Test
	void onlyOffTurnsTheWatchdogOff() {
		assertFalse(BridgeConfig.fromEnv(Map.of("BRIDGE_WATCHDOG", "off")).watchdog());
		assertFalse(BridgeConfig.fromEnv(Map.of("BRIDGE_WATCHDOG", " OFF ")).watchdog());
		assertTrue(BridgeConfig.fromEnv(Map.of("BRIDGE_WATCHDOG", "on")).watchdog());
		assertTrue(BridgeConfig.fromEnv(Map.of("BRIDGE_WATCHDOG", "false")).watchdog(), "a typo does not switch the safety net off");
	}

	@Test
	void durableReplaySettings() {
		BridgeConfig c = BridgeConfig.fromEnv(Map.of("BRIDGE_DATA_DIR", " /data ", "BRIDGE_REPLAY_MAX", "500"));
		assertEquals("/data", c.dataDir());
		assertEquals(500, c.replayMax());
		assertThrows(IllegalArgumentException.class, () -> BridgeConfig.fromEnv(Map.of("BRIDGE_REPLAY_MAX", "0")));
	}

	@Test
	void onlyTrueEnablesOrders() {
		assertTrue(BridgeConfig.fromEnv(Map.of("BRIDGE_ORDERS_ENABLED", "true")).ordersEnabled());
		assertTrue(BridgeConfig.fromEnv(Map.of("BRIDGE_ORDERS_ENABLED", "TRUE")).ordersEnabled());
		assertFalse(BridgeConfig.fromEnv(Map.of("BRIDGE_ORDERS_ENABLED", "yes")).ordersEnabled());
		assertFalse(BridgeConfig.fromEnv(Map.of("BRIDGE_ORDERS_ENABLED", "1")).ordersEnabled());
	}

	@Test
	void readsOverrides() {
		BridgeConfig c = BridgeConfig.fromEnv(Map.of("IB_HOST", "gw", "IB_PORT", "4001", "IB_CLIENT_ID", "21",
				"BRIDGE_BIND", "0.0.0.0", "BRIDGE_PORT", "9000", "IB_MARKET_DATA_TYPE", "3"));
		assertEquals("gw", c.ibHost());
		assertEquals(4001, c.ibPort());
		assertEquals(21, c.ibClientId());
		assertEquals("0.0.0.0", c.bind());
		assertEquals(9000, c.port());
		assertEquals(3, c.marketDataType());
	}

	@Test
	void theOrderRoutesHaveNoTokenUnlessOneIsGiven() {
		assertNull(BridgeConfig.fromEnv(Map.of()).orderToken());
		assertNull(BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN", "   ")).orderToken());
		assertNull(BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN_FILE", " ")).orderToken());
	}

	@Test
	void theOrderTokenComesFromTheVariableOrTheFileAndTheFileWins(@TempDir Path dir) throws Exception {
		assertEquals("0123456789abcdef", BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN", " 0123456789abcdef ")).orderToken());
		Path file = Files.writeString(dir.resolve("bridge_order_token"), "token-from-the-file-1\n");
		assertEquals("token-from-the-file-1", BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN_FILE", file.toString())).orderToken());
		assertEquals("token-from-the-file-1", BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN_FILE", file.toString(),
				"BRIDGE_ORDER_TOKEN", "token-from-the-variable")).orderToken(), "the file wins");
	}

	@Test
	void anOrderTokenThatCannotBeUsedFailsTheStartAndNeverQuotesItsValue(@TempDir Path dir) throws Exception {
		IllegalArgumentException tooShort = assertThrows(IllegalArgumentException.class,
				() -> BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN", "short-secret")));
		assertFalse(tooShort.getMessage().contains("short-secret"), tooShort.getMessage());
		assertTrue(tooShort.getMessage().contains("16"), tooShort.getMessage());
		Path shortFile = Files.writeString(dir.resolve("short"), "short-secret\n");
		IllegalArgumentException shortInFile = assertThrows(IllegalArgumentException.class,
				() -> BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN_FILE", shortFile.toString())));
		assertFalse(shortInFile.getMessage().contains("short-secret"), shortInFile.getMessage());
		Path empty = Files.writeString(dir.resolve("empty"), "\n");
		IllegalArgumentException emptyFile = assertThrows(IllegalArgumentException.class, () -> BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN_FILE", empty.toString())));
		assertTrue(emptyFile.getMessage().contains("is empty") && emptyFile.getMessage().contains(empty.toString()), emptyFile.getMessage());
		assertSurvivesTheVmMasking(emptyFile.getMessage());
		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
				() -> BridgeConfig.fromEnv(Map.of("BRIDGE_ORDER_TOKEN_FILE", dir.resolve("missing").toString())),
				"a token file that is named but cannot be read does not start the bridge without the token");
		assertTrue(missing.getMessage().contains("cannot be read") && missing.getMessage().contains(dir.resolve("missing").toString()), missing.getMessage());
		assertSurvivesTheVmMasking(missing.getMessage());
		assertSurvivesTheVmMasking(tooShort.getMessage());
	}

	/**
	 * The VM's masking (infra/vm/mask.sh in mts-cloud) hides what follows a name with "token" in it and a colon or an equals sign: the first
	 * roll-out of the order token printed "BRIDGE_ORDER_TOKEN_FILE: cannot read ..." as "<masked> read ..." and the cause was gone from the log.
	 */
	private static void assertSurvivesTheVmMasking(String message) {
		assertFalse(java.util.regex.Pattern.compile("[A-Za-z0-9_.-]*(?i:token|secret|passw(or)?d)[A-Za-z0-9_.-]*\"?[ ]*[=:]").matcher(message).find(),
				"a message the VM's masking would cut: " + message);
	}

	@Test
	void rejectsInvalidValues() {
		assertThrows(IllegalArgumentException.class, () -> BridgeConfig.fromEnv(Map.of("IB_PORT", "abc")));
		assertThrows(IllegalArgumentException.class, () -> BridgeConfig.fromEnv(Map.of("BRIDGE_PORT", "70000")));
		assertThrows(IllegalArgumentException.class, () -> BridgeConfig.fromEnv(Map.of("IB_MARKET_DATA_TYPE", "5")));
		assertThrows(IllegalArgumentException.class, () -> BridgeConfig.fromEnv(Map.of("IB_CLIENT_ID", "-1")));
	}
}
