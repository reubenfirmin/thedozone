package tdz.controller.events

import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.card.CardListItem
import web.uievents.MouseEvent

interface Event

data class TaskToggleDone(val sourceEvent: MouseEvent, val canvasName: CanvasName, val card: Card, val segment: CardListItem): Event