package tdz.controller.events

import js.array.asList
import tdz.eventLog
import web.html.HTMLElement
import web.mutation.MutationObserver
import web.mutation.MutationObserverInit
import web.mutation.MutationRecordType
import zoned.framework.Debugger.debug
import kotlin.reflect.KClass

// Helper to access MutationRecordType.childList - works around Kotlin/JS type inference issues
// with kotlin-wrappers string-enum companion constants.
@PublishedApi
internal val CHILD_LIST: MutationRecordType
    get() = "childList".asDynamic().unsafeCast<MutationRecordType>()

class EventBus {

    // queue of events to be processed; a fired event won't necessarily be immediately processed
    private val executionQueue = mutableListOf<Event>()

    // actions that should run whenever an event of a given type happens
    val eventRegistry = mutableMapOf<KClass<out Event>, MutableList<(Event) -> Unit>>()


    // actions associated with components that should run whenever event of a type happens
    val componentRegistry = mutableMapOf<KClass<out Event>, MutableMap<HTMLElement, (@UnsafeVariance Event) -> Unit>>()

    // event that's currently executing, if any
    private var currentlyExecuting: Event? = null

    val observers = mutableMapOf<HTMLElement, MutationObserver>()

    /**
     * A global event. Use this to subscribe to events if the subscriber is in global state,
     * i.e. will never be removed during the lifetime of the application.
     *
     * Once subscribed, always subscribed. I.e. you should not delete components that re subscribe to
     * events.
     *
     * Do not rely on matches firing in any particular order.
     */
    inline fun <reified T : Event> on(noinline action: (T) -> Unit): EventBus {
        val eventType = T::class

        @Suppress("UNCHECKED_CAST")
        eventRegistry.getOrPut(eventType) { mutableListOf() }.add(action as (Event) -> Unit)
        return this
    }

    /**
     * Automatically prunes the event subscription when the component is removed. Also does
     * not add duplicates, so can be called repeatedly for the same component.
     */
    inline fun <reified T : Event> HTMLElement.on(noinline action: (T) -> Unit) {
        val eventType = T::class

        @Suppress("UNCHECKED_CAST")
        componentRegistry.getOrPut(eventType) { mutableMapOf() }[this] = action as (Event) -> Unit

        val observer = MutationObserver { mutations, observer ->
            for (mutation in mutations) {
                if (mutation.type == CHILD_LIST) {
                    mutation.removedNodes.asList().find { it == this }?.let {
                        // Element was removed from the DOM
                        componentRegistry[eventType]?.remove(this)
                        observer.disconnect()
                        observers.remove(this)
                    }
                }
            }
        }

        observers[this] = observer

        // Start observing the parent node for child removals
        this.parentNode?.let { parentNode ->
            observer.observe(parentNode, MutationObserverInit(childList = true))
        }
    }

    /**
     * Remove all event types associated with this component.
     */
    fun removeAll(element: HTMLElement) {
        componentRegistry.forEach { (_, map) ->
            map.remove(element)
        }
        observers[element]?.disconnect()
        observers.remove(element)
    }

    /**
     * Send exception to error handlers registered to listen for it; throws exception.
     */
    fun fire(exception: Exception): Nothing {
        throw exception
    }

    /**
     * Will either run the event if no other events are executing, or queue up the event to be run and return
     * immediately. Order of events is preserved, but you cannot rely on the event having executed after this
     * function has been called.
     */
    fun fire(event: Event) {
        executionQueue.add(event)

        // don't immediately do work if there's stuff ahead of this
        if (executionQueue.size > 1) {
            return
        } else {
            doEvent(event)
        }
    }

    private fun doEvent(event: Event) {
        currentlyExecuting = event
        val actions = eventRegistry[event::class].orEmpty() + componentRegistry[event::class]?.values.orEmpty()

        debug("Event $event started", eventLog)

        fun finish(actionsFired: Int) {
            currentlyExecuting = null
            debug("Event $event finished, fired $actionsFired", eventLog)
            if (executionQueue.isEmpty() && actionsFired != 0) {
                console.warn("WARNING - event queue empty, should be at least 1, with $event")
            } else {
                executionQueue.removeFirst()
            }
            // recurse for remainder of queued events
            executionQueue.forEach { doEvent(it) }
        }

        var actionsFired = 0

        fun iterateActions(idx: Int) {
            actions[idx](event)
            actionsFired++
            if (actionsFired < actions.size) {
                iterateActions(idx + 1)
            } else {
                finish(actionsFired)
            }
        }

        if (actions.isNotEmpty()) {
            iterateActions(0)
        } else {
            finish(0)
        }
    }
}

