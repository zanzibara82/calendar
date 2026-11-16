package com.example.mycalendar.domain.util

import com.example.mycalendar.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RruleCodecTest {

    @Test
    fun `rule maps to rrule`() {
        assertEquals("FREQ=DAILY", RruleCodec.toRrule(RepeatRule.DAILY))
        assertEquals("FREQ=WEEKLY", RruleCodec.toRrule(RepeatRule.WEEKLY))
        assertEquals("FREQ=MONTHLY", RruleCodec.toRrule(RepeatRule.MONTHLY))
        assertEquals("FREQ=YEARLY", RruleCodec.toRrule(RepeatRule.YEARLY))
        assertNull(RruleCodec.toRrule(null))
    }

    @Test
    fun `rrule maps to rule and ignores other parts`() {
        assertEquals(RepeatRule.DAILY, RruleCodec.fromRrule("FREQ=DAILY"))
        assertEquals(RepeatRule.WEEKLY, RruleCodec.fromRrule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"))
        assertEquals(RepeatRule.MONTHLY, RruleCodec.fromRrule("FREQ=MONTHLY;COUNT=5"))
        assertEquals(RepeatRule.YEARLY, RruleCodec.fromRrule("FREQ=YEARLY"))
    }

    @Test
    fun `unknown and empty rules give null`() {
        assertNull(RruleCodec.fromRrule("FREQ=HOURLY"))
        assertNull(RruleCodec.fromRrule(""))
        assertNull(RruleCodec.fromRrule(null))
    }
}
