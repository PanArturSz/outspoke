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
    fun `10 zamiast ten wraca do zaimka tam, gdzie liczba nie ma sensu`() {
        assertEquals("ten przycisk na dole nie działa", n("10 przycisk na dole nie działa"))
        assertEquals("i powiem, że ten niedźwiedź", n("i powiem, że 10 niedźwiedź"))
        assertEquals("w ten sposób", n("w 10 sposób"))
        assertEquals("ten sam plik", n("10 sam plik"))
        assertEquals("Ten, który wygrał", n("10, który wygrał"))
        assertEquals("Ten problem", n("10 problem"))
    }

    @Test
    fun `10 zostaje liczbą, gdy jest liczbą`() {
        assertEquals("mam 10 minut", n("mam 10 minut"))
        assertEquals("o 10 rano", n("o 10 rano"))
        assertEquals("10 osób i 10 kotów", n("10 osób i 10 kotów"))
        assertEquals("ponad 10 firm", n("ponad 10 firm"))
        assertEquals("10 dni", n("10 dni"))
        assertEquals("strona 10.", n("strona 10."))
        assertEquals("2 razy 10 to 20", n("dwa razy dziesięć to dwadzieścia"))
    }

    @Test
    fun `interpunkcja i wielkość liter zachowane`() {
        assertEquals("(43) tak", n("(czterdzieści trzy) tak"))
        assertEquals("Dwa razy", n("Dwa razy"))
    }
}
