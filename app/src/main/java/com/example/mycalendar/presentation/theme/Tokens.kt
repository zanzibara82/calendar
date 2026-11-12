package com.example.mycalendar.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Геометрия интерфейса. Единственное место, где в UI стоят числа: экраны и компоненты
 * обязаны брать значения отсюда, иначе Windows-версия не сможет скопировать макет 1:1.
 */

object AppShapes {
    val card: Shape = RoundedCornerShape(18.dp)
    // Пилюля: 50 % высоты, а не фиксированный радиус — чип растёт вместе с текстом.
    val chip: Shape = RoundedCornerShape(percent = 50)
    val calendarCell: Shape = RoundedCornerShape(12.dp)
    val dayMarker: Shape = CircleShape
}

object AppSpacing {
    val screen: Dp = 16.dp
    val cardGap: Dp = 10.dp
    val cardHPad: Dp = 14.dp
    val cardVPad: Dp = 12.dp
    val providerStripe: Dp = 4.dp
    val chipHPad: Dp = 8.dp
    val chipVPad: Dp = 3.dp
    val rowGap: Dp = 6.dp
    val dotGap: Dp = 5.dp
    val headerVPad: Dp = 4.dp
    // Вертикальный отступ списков и шапки видов.
    val listVPad: Dp = 8.dp
    // Промежуток между строками событий внутри карточки дня и отступ текста от полосы.
    val weekCellGap: Dp = 4.dp
    // Отступ маркера праздника от угла: клетка месяца меньше карточки, но отступ один.
    val markInset: Dp = 4.dp
    // Высота кнопки «Новое событие» с отступами: столько списки оставляют снизу,
    // иначе последняя карточка уходит под кнопку.
    val fabClearance: Dp = 88.dp
}

object AppSizes {
    val timeColumn: Dp = 56.dp
    val segmentedHeight: Dp = 40.dp
    val minTouch: Dp = 48.dp
    val iconSmall: Dp = 16.dp
    val iconMedium: Dp = 18.dp
    val dayHeaderMinHeight: Dp = 28.dp
    val dot: Dp = 6.dp
    // Точки провайдера бывают трёх размеров; все три значения — токен, а не число по месту.
    val gridDot: Dp = 5.dp
    val detailsDot: Dp = 12.dp
    val stroke: Dp = 1.dp
    // Сколько строк событий помещается в карточке дня без «+N ещё».
    val weekVisibleCells: Int = 4
}
