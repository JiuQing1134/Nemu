package com.nemu.hibernate.core

/**
 * Recovery-critical roles that cannot be recognized by a package-name
 * heuristic: third-party launchers and input methods. The UI reads the
 * installed roles from PackageManager; this pure merge is unit-tested.
 */
object CriticalRoles {
    fun roles(
        homePackages: Collection<String>,
        imePackages: Collection<String>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        homePackages.forEach { result[it] = "桌面" }
        imePackages.forEach { packageName ->
            result[packageName] = when (result[packageName]) {
                "桌面" -> "桌面/输入法"
                else -> "输入法"
            }
        }
        return result
    }
}
