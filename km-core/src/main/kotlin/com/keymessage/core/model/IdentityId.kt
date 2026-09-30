package com.keymessage.core.model

@JvmInline
value class IdentityId(val value: String) : Comparable<IdentityId> {
    override fun compareTo(other: IdentityId): Int = value.compareTo(other.value)
}
