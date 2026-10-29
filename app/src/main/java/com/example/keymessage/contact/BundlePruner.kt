package com.example.keymessage.contact

/**
 * El podador del bundle: DELEGACION al de km-core.
 *
 * Estaba en la app porque solo la app fabricaba bundles; desde G1 tambien los
 * fabrica el escritorio, y dos podadores producen dos documentos para el mismo
 * material. La forma del documento vive en el framework.
 */
object BundlePruner {
    fun pruneDeviceEntry(entry: Map<String, Any?>): Map<String, Any?> =
        com.km.identity.BundlePruner.pruneDeviceEntry(entry)

    fun pruneRoster(roster: Map<String, Any?>): Map<String, Any?> =
        com.km.identity.BundlePruner.pruneRoster(roster)

    fun pruneSignedPrekey(spk: Map<String, Any?>): Map<String, Any?> =
        com.km.identity.BundlePruner.pruneSignedPrekey(spk)

    fun pruneBundle(bundle: Map<String, Any?>): Map<String, Any?> =
        com.km.identity.BundlePruner.pruneBundle(bundle)
}
