package com.nemu.hibernate.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * Global "no press ripple" indication. Compose's Material ripples look like
 * stray grey shadows on text/icon controls; the product wants no press
 * overlay anywhere, while clicks and state changes remain unchanged.
 */
internal object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}
