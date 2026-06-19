package tdz.sync

/** The remote backends the sync vertical can target. The user picks exactly one at a time. The enum
 *  itself lives in the persistence layer (it's a stored config field); re-exported here so the sync
 *  vertical refers to it without the model depending on `tdz.sync`. */
typealias SyncProvider = tdz.model.localstorage.SyncProvider
