package dev.brgr.outspoke.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class PolishNumbersTest {
    private fun n(s: String) = PolishNumbers.normalise(s)

    @Test
    fun `ciągi liczebników głównych`() {
        assertEquals("68", n("sześćdziesiąt osiem"))
        assertEquals("A teraz liczby, 68, 79, 593.", n("A teraz liczby, sześćdziesiąt osiem, siedemdziesiąt dziewięć, pięćset dziewięćdziesiąt trzy."))
        assertEquals("2026 rok", n("dwa tysiące dwadzieścia sześć rok"))
        assertEquals("5000 złotych", n("pięć tysięcy złotych"))
    }

    @Test
    fun `milion zostaje słowem, małe liczby zostają słowem`() {
        assertEquals("linia 250 milionów", n("linia dwieście pięćdziesiąt milionów"))
        assertEquals("dwa dni i jedna rzecz", n("dwa dni i jedna rzecz"))
        assertEquals("tysiąc razy", n("tysiąc razy"))
        assertEquals("12 osób", n("dwanaście osób"))
    }

    @Test
    fun `data z porządkowym`() {
        assertEquals("Spotkanie 9 września o 14:30", n("Spotkanie dziewiątego września o czternastej trzydzieści"))
        assertEquals("do 21 maja", n("do dwudziestego pierwszego maja"))
        assertEquals("pierwszego dnia", n("pierwszego dnia"))
    }

    @Test
    fun `godzina`() {
        assertEquals("o 8:30 rano", n("o ósmej trzydzieści rano"))
        assertEquals("po pierwszej rozmowie", n("po pierwszej rozmowie"))
        assertEquals("o 21:15.", n("o dwudziestej pierwszej piętnaście."))
        assertEquals("około 10:00", n("około dziesiątej"))
    }

    @Test
    fun `interpunkcja i wielkość liter zachowane`() {
        assertEquals("(43) tak", n("(czterdzieści trzy) tak"))
        assertEquals("Dwa razy", n("Dwa razy"))
    }
}
