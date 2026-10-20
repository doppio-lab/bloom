package lab.doppio.bloom.app.web

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvCellTest {
    @Test
    fun `a cell that a spreadsheet would run as a formula is neutralised`() {
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", csvCell("=HYPERLINK(\"http://x\")"))
        assertEquals("\"'+1\"", csvCell("+1"))
        assertEquals("\"'-1\"", csvCell("-1"))
        assertEquals("\"'@SUM(A1)\"", csvCell("@SUM(A1)"))
        assertEquals("\"'\tx\"", csvCell("\tx"))
        assertEquals("\"'\rx\"", csvCell("\rx"))
    }

    @Test
    fun `an ordinary cell is only quoted`() {
        assertEquals("\"Velomoov\"", csvCell("Velomoov"))
        assertEquals("\"Dupont \"\"Le Boss\"\"\"", csvCell("Dupont \"Le Boss\""))
        assertEquals("\"\"", csvCell(""))
    }
}
