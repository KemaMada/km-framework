package com.km.identity

/**
 * Podado de campos DERIVABLES del bundle para que quepa en un QR.
 *
 * Los campos que se quitan se derivan o tienen valor por defecto en el
 * verificador de km-core (`identityId`, `subjectSigningKey`, `status`,
 * `createdAt`, ...), de modo que el documento sigue verificando. Los campos
 * criptograficos —claves, firmas, `protocolVersion`— NO se tocan.
 *
 * ## POR QUE ESTA EN km-core
 *
 * Porque es parte de la FORMA del documento que viaja, no de la pantalla que lo
 * pinta. Estaba en la app y el escritorio habria tenido que copiarlo; dos
 * podadores distintos producen bundles distintos para el mismo material.
 */
object BundlePruner {

    fun pruneDeviceEntry(entry: Map<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap(entry)
        m.remove("deviceId")
        m.remove("status")
        m.remove("addedAt")
        m.remove("name")
        return m
    }

    fun pruneRoster(roster: Map<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap(roster)
        m.remove("doc")
        m.remove("identityRoot")
        m.remove("identityId")
        m.remove("sequence")
        m.remove("previous")
        m.remove("createdAt")
        val devices = (m["devices"] as? List<Map<String, Any?>>)?.map { pruneDeviceEntry(it) }
        if (devices != null) m["devices"] = devices
        return m
    }

    fun pruneSignedPrekey(spk: Map<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap(spk)
        m.remove("doc")
        m.remove("deviceId")
        m.remove("createdAt")
        m.remove("expiresAt")
        return m
    }

    fun pruneBundle(bundle: Map<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap(bundle)
        m.remove("doc")
        m.remove("kind")
        m.remove("createdAt")
        m.remove("identityId")
        m.remove("capabilities")
        m.remove("subjectSigningKey")
        m.remove("subjectAgreementKey")
        if ((m["oneTimePrekeys"] as? List<*>)?.isEmpty() == true) m.remove("oneTimePrekeys")
        if ((m["endpoints"] as? List<*>)?.isEmpty() == true) m.remove("endpoints")
        val roster = m["roster"] as? Map<String, Any?>
        if (roster != null) m["roster"] = pruneRoster(roster)
        val spk = m["signedPrekey"] as? Map<String, Any?>
        if (spk != null) m["signedPrekey"] = pruneSignedPrekey(spk)
        return m
    }
}
