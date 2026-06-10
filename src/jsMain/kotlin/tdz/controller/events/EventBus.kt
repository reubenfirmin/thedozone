package tdz.controller.events

/**
 * The app's event bus: zoned's generic bus typed to this app's [Event] hierarchy. The mechanism
 * (global on/off, element-scoped auto-pruning subscriptions, ordered dispatch queue) lives in the
 * framework; only the event taxonomy ([Event] and friends) is application code.
 */
typealias EventBus = zoned.framework.events.EventBus<Event>
