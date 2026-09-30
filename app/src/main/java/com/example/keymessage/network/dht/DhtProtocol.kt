package com.example.keymessage.network.dht

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress

data class NodeInfo(
    val nodeId: String,
    val publicKey: String,
    val ip: String,
    val port: Int
)

sealed class DhtMessage {
    abstract val nodeId: String
    abstract fun toJson(): JSONObject

    data class Ping(override val nodeId: String) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "ping")
            put("nodeId", nodeId)
        }
    }

    data class Pong(override val nodeId: String) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "pong")
            put("nodeId", nodeId)
        }
    }

    data class FindNode(
        override val nodeId: String,
        val targetNodeId: String
    ) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "find_node")
            put("nodeId", nodeId)
            put("target", targetNodeId)
        }
    }

    data class Nodes(
        override val nodeId: String,
        val nodes: List<String> // list of nodeIds
    ) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "nodes")
            put("nodeId", nodeId)
            put("nodes", JSONObject().apply {
                nodes.forEachIndexed { i, id -> put(i.toString(), id) }
            }.toString())
        }
    }

    data class Store(
        override val nodeId: String,
        val key: String,
        val value: String // serialized message
    ) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "store")
            put("nodeId", nodeId)
            put("key", key)
            put("value", value)
        }
    }

    data class Stored(override val nodeId: String, val key: String) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "stored")
            put("nodeId", nodeId)
            put("key", key)
        }
    }

    data class FindValue(override val nodeId: String, val key: String) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "find_value")
            put("nodeId", nodeId)
            put("key", key)
        }
    }

    data class Value(override val nodeId: String, val key: String, val value: String) : DhtMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "value")
            put("nodeId", nodeId)
            put("key", key)
            put("value", value)
        }
    }

    companion object {
        fun fromJson(json: String): DhtMessage? {
            return try {
                val obj = JSONObject(json)
                val type = obj.optString("type")
                val nodeId = obj.optString("nodeId", "")
                when (type) {
                    "ping" -> Ping(nodeId)
                    "pong" -> Pong(nodeId)
                    "find_node" -> FindNode(nodeId, obj.optString("target", ""))
                    "nodes" -> {
                        val raw = obj.optString("nodes", "{}")
                        val map = JSONObject(raw)
                        val list = mutableListOf<String>()
                        for (k in map.keys()) list.add(map.optString(k, ""))
                        Nodes(nodeId, list)
                    }
                    "store" -> Store(nodeId, obj.optString("key", ""), obj.optString("value", ""))
                    "stored" -> Stored(nodeId, obj.optString("key", ""))
                    "find_value" -> FindValue(nodeId, obj.optString("key", ""))
                    "value" -> Value(nodeId, obj.optString("key", ""), obj.optString("value", ""))
                    else -> null
                }
            } catch (_: Exception) { null }
        }
    }
}
