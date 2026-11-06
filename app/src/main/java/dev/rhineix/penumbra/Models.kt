package dev.rhineix.penumbra

object PackageRules {
    private val pattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    fun validate(name: String): String {
        require(name.length <= 255 && pattern.matches(name)) { "Invalid package name" }
        return name
    }
    fun mayManage(name: String, own: String, protected: Set<String>): Boolean =
        name != own && name != "android" && name !in protected
}

data class AppEntry(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val suspended: Boolean,
    val hidden: Boolean,
    val protected: Boolean,
)

