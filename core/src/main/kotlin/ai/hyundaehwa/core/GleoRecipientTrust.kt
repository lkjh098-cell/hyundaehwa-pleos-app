package ai.hyundaehwa.core

/** Identity supplied only after OEM confirmation; package discovery is never a trust decision. */
object GleoRecipientTrust {
    fun configured(packageName: String, certificateSha256: String): Boolean =
        Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName) &&
            Regex("[A-Fa-f0-9]{64}").matches(certificateSha256)

    fun matches(packageName: String, certificateSha256: String, installedSignerDigests: List<String>): Boolean =
        configured(packageName, certificateSha256) && installedSignerDigests.size == 1 &&
            installedSignerDigests.single().equals(certificateSha256, ignoreCase = true)
}
