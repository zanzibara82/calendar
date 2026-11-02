package com.example.mycalendar.presentation.common

import androidx.annotation.StringRes
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.EventTag

/** Подпись метки события: чипы в карточке, детали и редактор. */
@get:StringRes
val EventTag.labelRes: Int
    get() = when (this) {
        EventTag.BIRTHDAY -> R.string.tag_birthday
        EventTag.DATE -> R.string.tag_date
        EventTag.CINEMA -> R.string.tag_cinema
    }
