@file:JsModule("node-diff3")
@file:JsNonModule
package tdz.sync

/**
 * Minimal external surface of the `node-diff3` npm package. `diff3Merge` does a 3-way line merge:
 * each returned region is `{ ok: string[] }` (agreed/auto-merged) or `{ conflict: {...} }`. We pass
 * arrays of lines. Response shapes are dynamic and mapped in [ColumnMerge] only.
 */
external fun diff3Merge(a: Array<String>, o: Array<String>, b: Array<String>): Array<dynamic>
