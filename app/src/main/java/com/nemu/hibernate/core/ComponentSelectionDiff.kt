package com.nemu.hibernate.core

/**
 * Components Nemu owns but the user no longer selected. Restoration happens
 * on the next Apply, never as an implicit side effect of closing the picker.
 */
object ComponentSelectionDiff {
    fun deselected(
        owned: Collection<String>,
        selected: Collection<String>
    ): Set<String> = owned.filterNot { it in selected }.toSet()
}
