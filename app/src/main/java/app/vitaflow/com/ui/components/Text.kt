package app.vitaflow.com.ui.components

/** Lets people type Bengali digits (১২৩) in number fields. */
fun String.asciiDigits(): String = map { c ->
    if (c in '\u09E6'..'\u09EF') '0' + (c - '\u09E6') else c
}.joinToString("")

fun String.toIntLoose(): Int? = asciiDigits().trim().toIntOrNull()

fun String.toDoubleLoose(): Double? = asciiDigits().trim().replace(',', '.').toDoubleOrNull()
