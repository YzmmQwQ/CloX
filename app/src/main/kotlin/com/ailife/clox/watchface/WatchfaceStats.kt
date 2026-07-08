package com.ailife.clox.watchface

/**
 * Live statistics about the currently-loaded watchface, reported from the Lua
 * engine after each face build and read by the config dialog's Information tab.
 * Volatile single-slot holder — there is only ever one active face.
 */
object WatchfaceStats {
    /** Layer count of the loaded face (-1 = not yet reported). */
    @Volatile var layerCount: Int = -1
        private set

    /** The face path the [layerCount] belongs to (null = default face / unknown). */
    @Volatile var layerCountPath: String? = null
        private set

    fun setLayerCount(count: Int, path: String?) {
        layerCount = count
        layerCountPath = path
    }

    /** Layers merged into cached textures by the flatten optimization (-1 = not reported). */
    @Volatile var mergedLayers: Int = -1
        private set

    /** Number of cached run textures the merged layers were baked into. */
    @Volatile var mergedRuns: Int = 0
        private set

    fun setMerged(merged: Int, runs: Int) {
        mergedLayers = merged
        mergedRuns = runs
    }

    /** Persisted script-variable backup size in bytes (-1 = not yet reported). */
    @Volatile var storageBytes: Int = -1
        private set

    fun setStorageBytes(bytes: Int) {
        storageBytes = bytes
    }
}
