package com.example.mycalendar.presentation.conflict

import com.example.mycalendar.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Форму счётчика выбираем по русским правилам, а не через plurals: правила
 * склонения Android берёт из локали устройства, и на en-US для «2» выходило
 * «2 спорного поля».
 */
class ContestedFieldsLabelTest {

    @Test
    fun `one field uses singular form`() {
        assertEquals(R.string.conflict_contested_fields_one, contestedFieldsLabel(1))
        assertEquals(R.string.conflict_contested_fields_one, contestedFieldsLabel(21))
        assertEquals(R.string.conflict_contested_fields_one, contestedFieldsLabel(101))
    }

    @Test
    fun `two to four fields use few form`() {
        assertEquals(R.string.conflict_contested_fields_few, contestedFieldsLabel(2))
        assertEquals(R.string.conflict_contested_fields_few, contestedFieldsLabel(3))
        assertEquals(R.string.conflict_contested_fields_few, contestedFieldsLabel(4))
        assertEquals(R.string.conflict_contested_fields_few, contestedFieldsLabel(22))
    }

    @Test
    fun `eleven to fourteen and five plus use many form`() {
        assertEquals(R.string.conflict_contested_fields_many, contestedFieldsLabel(5))
        assertEquals(R.string.conflict_contested_fields_many, contestedFieldsLabel(11))
        assertEquals(R.string.conflict_contested_fields_many, contestedFieldsLabel(12))
        assertEquals(R.string.conflict_contested_fields_many, contestedFieldsLabel(13))
        assertEquals(R.string.conflict_contested_fields_many, contestedFieldsLabel(14))
        assertEquals(R.string.conflict_contested_fields_many, contestedFieldsLabel(25))
    }
}
