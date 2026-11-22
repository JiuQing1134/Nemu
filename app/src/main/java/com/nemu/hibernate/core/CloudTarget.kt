package com.nemu.hibernate.core

enum class CloudAction {
    DISABLE_USER,
    SUSPEND,
    OBSERVE_ONLY
}

data class CloudTarget(
    val packageName: String,
    val label: String,
    val action: CloudAction,
    val description: String
)
