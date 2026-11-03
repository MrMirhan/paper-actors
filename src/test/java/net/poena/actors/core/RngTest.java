package net.poena.actors.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Reference values come from agent/js/human.js (node), so every body rolls the same dice for the same seed. */
class RngTest {
    @Test
    void sameStreamAsTheJavaScriptSideForAStringSeed() {
        Rng r = new Rng("take1");
        double[] want = {0.9127466725185513, 0.037296099588274956, 0.2682884023524821, 0.4747302127070725, 0.5330866950098425};
        double[] got = new double[5];
        for (int i = 0; i < 5; i++) {
            got[i] = r.next();
        }
        assertArrayEquals(want, got, 0.0);
    }

    @Test
    void sameStreamForANumericSeed() {
        Rng r = new Rng(42L);
        assertArrayEquals(new double[] {0.6011037519201636, 0.44829055899754167, 0.8524657934904099}, new double[] {r.next(), r.next(), r.next()}, 0.0);
    }

    @Test
    void unicodeSeedMatches() {
        assertEquals(0.6775201733689755, new Rng("é😀z").next(), 0.0);
    }

    @Test
    void normalRangeAndChanceMatch() {
        Rng r = new Rng("take1");
        assertEquals(238.7033867359786, r.normal(220, 45), 1e-9);
        assertEquals(8.804865207057446, r.range(8, 11), 1e-12);
        assertTrue(r.chance(0.5));
    }

    @Test
    void jsonSeedAcceptsStringsAndNumbers() {
        assertEquals(new Rng("take1").next(), Rng.fromJson(JsonParser.parseString("\"take1\"")).next(), 0.0);
        assertEquals(new Rng(42L).next(), Rng.fromJson(JsonParser.parseString("42")).next(), 0.0);
        assertEquals(new Rng(1L).next(), Rng.fromJson(null).next(), 0.0);
    }

    @Test
    void differentSeedsDiffer() {
        assertNotEquals(new Rng("a").next(), new Rng("b").next());
    }
}
