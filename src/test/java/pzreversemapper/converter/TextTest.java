package pzreversemapper.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextTest {
    @Test
    void tryParseIntFollowsDotNetRules() {
        assertEquals(42, Text.tryParseInt("42"));
        assertEquals(-7, Text.tryParseInt(" -7 "));
        assertEquals(5, Text.tryParseInt("+5"));
        assertNull(Text.tryParseInt(""));
        assertNull(Text.tryParseInt("-"));
        assertNull(Text.tryParseInt("1x"));
        assertNull(Text.tryParseInt("2147483648"));
        assertNull(Text.tryParseInt("\u0661\u0662"));
    }

    @Test
    void sanitizeFileNameUsesWindowsInvalidCharacters() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", Text.sanitizeFileName("a<b>c:d\"e/f\\g|h?i*j"));
        assertEquals("tab_x", Text.sanitizeFileName("tab\tx"));
        assertEquals("plain name.tbx", Text.sanitizeFileName("plain name.tbx"));
    }

    @Test
    void ordinalIgnoreCaseComparesUppercasedCharacters() {
        assertTrue(Text.compareOrdinalIgnoreCase("Tiles2x.floor.pack", "tiles2x.pack") < 0);
        assertTrue(Text.compareOrdinalIgnoreCase("A", "_") < 0, "'A' (0x41) sorts before '_' (0x5F) after uppercasing");
        assertTrue(Text.equalsOrdinalIgnoreCase("Blends_Natural", "blends_natural"));
        assertEquals(Text.foldCase("Blends_Natural"), Text.foldCase("BLENDS_natural"));
    }

    @Test
    void cultureOrderPutsPunctuationBeforeDigitsAndLetters() {
        List<String> names = new ArrayList<>(List.of(
            "media/tiledefinitions.tiles",
            "media/tiledefinitions_extra.tiles",
            "media/newtiledefinitions.tiles",
            "10_5.lotheader",
            "1_5.lotheader",
            "B.txt",
            "a.txt"));
        names.sort(Text.CULTURE_ORDER);
        assertEquals(List.of(
            "1_5.lotheader",
            "10_5.lotheader",
            "a.txt",
            "B.txt",
            "media/newtiledefinitions.tiles",
            "media/tiledefinitions_extra.tiles",
            "media/tiledefinitions.tiles"), names);
    }

    @Test
    void whitespaceMatchesDotNet() {
        assertTrue(Text.isWhiteSpace('\u00a0'));
        assertTrue(Text.isWhiteSpace('\u2003'));
        assertFalse(Text.isWhiteSpace('x'));
        assertEquals("x", Text.trim("\u00a0 x\t\r\n"));
        assertTrue(Text.isNullOrWhiteSpace(" \u3000 "));
    }

    @Test
    void pathHelpersAcceptBothSeparators() {
        assertEquals("blends_natural_01", Text.fileNameWithoutExtension("Tiles\\2x/blends_natural_01.png"));
        assertEquals(".pack", Text.extension("Tiles2x.floor.pack"));
        assertEquals("MyMap", Text.fileName(Text.trimPath("D:\\Maps\\MyMap\\")));
    }
}
