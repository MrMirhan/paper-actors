package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateTest {
    @Test
    void emptyAllowlistRefusesEverything() {
        assertTrue(Gate.refuseServer("poena-film", 25565, List.of()).isPresent());
        assertTrue(Gate.refuseServer("poena-film", 25565, null).isPresent());
    }

    @Test
    void listedServerNameIsAccepted() {
        assertTrue(Gate.refuseServer("Poena-Film", 25565, List.of("poena-film")).isEmpty());
        assertTrue(Gate.refuseServer("poena-film", 25570, List.of("poena-film:25570")).isEmpty());
    }

    @Test
    void otherServerIsRefused() {
        assertTrue(Gate.refuseServer("lobi-1", 25565, List.of("poena-film", "poena-test")).isPresent());
        assertTrue(Gate.refuseServer("poena-film", 25566, List.of("poena-film:25570")).isPresent());
    }

    @Test
    void wildcardIsNotAWildcard() {
        assertTrue(Gate.refuseServer("lobi-1", 25565, List.of("*")).isPresent());
    }

    @Test
    void defaultOrMissingServerNameIsRefusedEvenWhenListed() {
        assertTrue(Gate.refuseServer("Unknown Server", 25565, List.of("Unknown Server")).isPresent());
        assertTrue(Gate.refuseServer("", 25565, List.of("")).isPresent());
        assertTrue(Gate.refuseServer(null, 25565, List.of("x")).isPresent());
    }

    @Test
    void secretMustBeLongEnough() {
        assertTrue(Gate.refuseSecret(null).isPresent());
        assertTrue(Gate.refuseSecret("").isPresent());
        assertTrue(Gate.refuseSecret("short").isPresent());
        assertTrue(Gate.refuseSecret("0123456789abcdef").isEmpty());
    }

    @Test
    void namesFollowMinecraftRules() {
        assertTrue(Gate.validName("Rambox"));
        assertTrue(Gate.validName("a_b"));
        assertFalse(Gate.validName("ab"));
        assertFalse(Gate.validName("seventeen_chars_xx"));
        assertFalse(Gate.validName("bad name"));
        assertFalse(Gate.validName("../x"));
        assertFalse(Gate.validName(null));
    }

    @Test
    void reservedNamesMatchExactlyOrByPrefix() {
        List<String> reserved = List.of("PoenaCam", "PoenaBot*");
        assertTrue(Gate.reserved("poenacam", reserved));
        assertTrue(Gate.reserved("PoenaBot7", reserved));
        assertFalse(Gate.reserved("PoenaCamX", reserved));
        assertFalse(Gate.reserved("Rambox", reserved));
    }

    @Test
    void onlyAllowlistedConsoleCommandsPass() {
        List<String> roots = List.of("time", "weather", "tick");
        assertTrue(Gate.commandAllowed("/time set day", roots));
        assertTrue(Gate.commandAllowed("weather clear", roots));
        assertTrue(Gate.commandAllowed("minecraft:tick rate 5", roots));
        assertFalse(Gate.commandAllowed("op Rambox", roots));
        assertFalse(Gate.commandAllowed("/stop", roots));
        assertFalse(Gate.commandAllowed("", roots));
        assertFalse(Gate.commandAllowed("time set day\nop Rambox", roots));
    }

    @Test
    void serverNameIsReadFromServerProperties(@TempDir Path dir) throws Exception {
        Path props = dir.resolve("server.properties");
        Files.writeString(props, "motd=hi\nserver-name=poena-film\n");
        assertEquals("poena-film", Gate.readServerName(props));
        assertEquals("", Gate.readServerName(dir.resolve("missing.properties")));
    }

    @Test
    void aRemoteApiNeedsALongSecret() {
        assertTrue(Gate.refuseRemote("127.0.0.1", "0123456789abcdef").isEmpty());
        assertTrue(Gate.refuseRemote("localhost", "0123456789abcdef").isEmpty());
        assertTrue(Gate.refuseRemote("0.0.0.0", "0123456789abcdef").isPresent());
        assertTrue(Gate.refuseRemote("10.0.0.5", "0123456789abcdef0123456789abcdef").isEmpty());
    }
}
