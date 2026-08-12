package com.nexterm.feature.distros

import android.os.Build

/**
 * The Linux environments NEXTERM can install, with the exact rootfs tarball for each
 * supported CPU architecture.
 *
 * URLs and checksums are taken from termux/proot-distro's distribution plug-ins, the
 * same artefacts proot-distro itself downloads. They are pinned here rather than
 * fetched at runtime so an install can be verified: a rootfs is executable code, and
 * a tarball whose SHA-256 does not match the pin is rejected instead of unpacked.
 *
 * Every entry lists only the architectures that genuinely have a published tarball.
 * A device whose ABI is absent is told so plainly — the alternative, offering a
 * download that cannot exist, is exactly the fake functionality this app avoids.
 */
object DistroCatalog {

    /** One downloadable rootfs, for one architecture. */
    data class Rootfs(
        val url: String,
        val sha256: String,
        /** Approximate download size, used for the progress UI before Content-Length. */
        val downloadBytes: Long,
        /** Leading path components to drop; proot-distro wraps in `<name>-<arch>/`. */
        val stripComponents: Int = 1,
    )

    data class Entry(
        val id: String,
        val displayName: String,
        val version: String,
        /** Shown under the name in the environments list. */
        val comment: String,
        /** Unpacked footprint, for the "needs ~N MB" warning before download. */
        val installedBytes: Long,
        /** Keyed by [Architecture]. Missing key = not published for that CPU. */
        val rootfs: Map<Architecture, Rootfs>,
    ) {
        fun rootfsFor(architecture: Architecture): Rootfs? = rootfs[architecture]

        val supportedArchitectures: Set<Architecture> get() = rootfs.keys
    }

    /** CPU architectures Android runs on, named as proot-distro names them. */
    enum class Architecture(val id: String, val displayName: String) {
        AARCH64("aarch64", "64-bit ARM"),
        ARM("arm", "32-bit ARM"),
        X86_64("x86_64", "64-bit x86"),
        I686("i686", "32-bit x86"),
        ;

        companion object {
            /**
             * The architecture of the *running process*. Build.SUPPORTED_ABIS[0] is
             * the right source: a 64-bit device running a 32-bit process must get a
             * 32-bit rootfs, because proot cannot bridge the two.
             */
            fun current(): Architecture? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "arm64-v8a" -> AARCH64
                "armeabi-v7a", "armeabi" -> ARM
                "x86_64" -> X86_64
                "x86" -> I686
                else -> null
            }
        }
    }

    /**
     * Checksums below are verbatim from proot-distro v4.7.0's plug-ins. They cover
     * the v4.6.0 release assets, which is what those plug-ins point at.
     */
    val entries: List<Entry> = listOf(
        Entry(
            id = "alpine",
            displayName = "Alpine Linux",
            version = "edge",
            comment = "Tiny musl-based distro. Fastest to install; apk package manager.",
            installedBytes = 12L * 1024 * 1024,
            rootfs = mapOf(
                Architecture.AARCH64 to Rootfs(
                    url = pd("alpine-aarch64-pd-v4.6.0.tar.xz"),
                    sha256 = "bffe6373dea84dce6a25c94f225ccdaec96c825710d655aa1f4cae79333edea6",
                    downloadBytes = 3_006_000,
                ),
                Architecture.ARM to Rootfs(
                    url = pd("alpine-arm-pd-v4.6.0.tar.xz"),
                    sha256 = "c89482950298b4ea1e6f2b2bab9eb00697b8fefa313d56585c4f38e8e4ff2860",
                    downloadBytes = 2_845_000,
                ),
                Architecture.X86_64 to Rootfs(
                    url = pd("alpine-x86_64-pd-v4.6.0.tar.xz"),
                    sha256 = "7b3d51714226cfe1bc1a115316e9d1d9ebc3ac2eb92389bc0e5b5f01ac04ee0b",
                    downloadBytes = 3_200_000,
                ),
                Architecture.I686 to Rootfs(
                    url = pd("alpine-i686-pd-v4.6.0.tar.xz"),
                    sha256 = "f002d1a02efdd5f7e6c81df69c99a74d0022eb56ab7558fa35ac4c5fe0d7d43b",
                    downloadBytes = 3_300_000,
                ),
            ),
        ),
        Entry(
            id = "debian",
            displayName = "Debian",
            version = "12 (bookworm)",
            comment = "Stable release. Large package archive; apt package manager.",
            installedBytes = 480L * 1024 * 1024,
            rootfs = mapOf(
                Architecture.AARCH64 to Rootfs(
                    url = pd("debian-aarch64-pd-v4.6.0.tar.xz"),
                    sha256 = "68dab31b46af61114014b54876c4f317be648ce8c76c0c6cbb5d6011d420886c",
                    downloadBytes = 42_894_344,
                ),
                Architecture.ARM to Rootfs(
                    url = pd("debian-arm-pd-v4.6.0.tar.xz"),
                    sha256 = "8298f99afef34b135bc86025d65d638a234068ede00bf2e93f6cc1e1dcfc0196",
                    downloadBytes = 41_473_492,
                ),
                Architecture.X86_64 to Rootfs(
                    url = pd("debian-x86_64-pd-v4.6.0.tar.xz"),
                    sha256 = "1cdf67f0d458d6109e527415691db7b27b9d374a29b17226cdd2d9f1aa7660ef",
                    downloadBytes = 45_001_300,
                ),
                Architecture.I686 to Rootfs(
                    url = pd("debian-i686-pd-v4.6.0.tar.xz"),
                    sha256 = "beb475580f74ed64b784602b27755e4178ed360a84f64e2bbeaf8372cb60ecdf",
                    downloadBytes = 45_280_760,
                ),
            ),
        ),
        Entry(
            id = "ubuntu",
            displayName = "Ubuntu",
            version = "23.10 (mantic)",
            comment = "Familiar apt userland. Not published for 32-bit x86.",
            installedBytes = 420L * 1024 * 1024,
            rootfs = mapOf(
                Architecture.AARCH64 to Rootfs(
                    url = pd("ubuntu-aarch64-pd-v4.6.0.tar.xz"),
                    sha256 = "18f4746d56d8d9d223690706febcd45bef607d6240f4d137bc80d9d42f5d764a",
                    downloadBytes = 23_843_976,
                ),
                Architecture.ARM to Rootfs(
                    url = pd("ubuntu-arm-pd-v4.6.0.tar.xz"),
                    sha256 = "a37d63ba774c6d92ec54657261a9fc38b3b904a0e23aba70e1f44eae069a1c15",
                    downloadBytes = 23_544_676,
                ),
                Architecture.X86_64 to Rootfs(
                    url = pd("ubuntu-x86_64-pd-v4.6.0.tar.xz"),
                    sha256 = "fc8bd25316640c12697c3960c3629dc824d725332fb5559b7c5a90b86fe5c269",
                    downloadBytes = 25_504_576,
                ),
            ),
        ),
        Entry(
            id = "archlinux",
            displayName = "Arch Linux",
            version = "rolling",
            comment = "Rolling release with pacman. ARM ports only.",
            installedBytes = 700L * 1024 * 1024,
            rootfs = mapOf(
                Architecture.AARCH64 to Rootfs(
                    url = pd("archlinux-aarch64-pd-v4.6.0.tar.xz"),
                    sha256 = "7e87d551845aedae5a111d1fdcc2f5a69b0805f365244f3fab3fe67cd4114f00",
                    downloadBytes = 130_000_000,
                ),
                Architecture.ARM to Rootfs(
                    url = pd("archlinux-arm-pd-v4.6.0.tar.xz"),
                    sha256 = "9edc60150ffdeae42b05fdcffdf06226641c442673f66b64af369504abe83a4b",
                    downloadBytes = 125_000_000,
                ),
            ),
        ),
        Entry(
            id = "fedora",
            displayName = "Fedora",
            version = "39",
            comment = "dnf userland. 64-bit CPUs only.",
            installedBytes = 900L * 1024 * 1024,
            rootfs = mapOf(
                Architecture.AARCH64 to Rootfs(
                    url = pd("fedora-aarch64-pd-v4.6.0.tar.xz"),
                    sha256 = "920caf3290ddaf9347de51ccadb0b6391c0244286072a6664fb1600eee360b9c",
                    downloadBytes = 190_000_000,
                ),
                Architecture.X86_64 to Rootfs(
                    url = pd("fedora-x86_64-pd-v4.6.0.tar.xz"),
                    sha256 = "49ffa79c24db6a2ee664b2e29268e534c11e1a984b694f8c56551ddb12dde8b3",
                    downloadBytes = 195_000_000,
                ),
            ),
        ),
        Entry(
            id = "void",
            displayName = "Void Linux",
            version = "rolling",
            comment = "Independent rolling release with xbps. All architectures.",
            installedBytes = 300L * 1024 * 1024,
            rootfs = mapOf(
                Architecture.AARCH64 to Rootfs(
                    url = pd("void-aarch64-pd-v4.6.0.tar.xz"),
                    sha256 = "423c73d0b3767477da5d763f01dfb1a8e5f8148468bcb0c86ca365a15dfeadc1",
                    downloadBytes = 60_000_000,
                ),
                Architecture.ARM to Rootfs(
                    url = pd("void-arm-pd-v4.6.0.tar.xz"),
                    sha256 = "728af450f28e4a562c8f7f57890aa0417b749ab5766c1107a9b57f075781f141",
                    downloadBytes = 58_000_000,
                ),
                Architecture.X86_64 to Rootfs(
                    url = pd("void-x86_64-pd-v4.6.0.tar.xz"),
                    sha256 = "12deb4ca4d9bfc7e612c8a4f4f6b719d9f6ab258c54db600aea31ab24e61a140",
                    downloadBytes = 62_000_000,
                ),
                Architecture.I686 to Rootfs(
                    url = pd("void-i686-pd-v4.6.0.tar.xz"),
                    sha256 = "8fa3b582ebf6c06603b975f1f7a95bac0d0c971ce79caae4c68fd9b9dc39fd1e",
                    downloadBytes = 61_000_000,
                ),
            ),
        ),
    )

    fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }

    private fun pd(asset: String) =
        "https://github.com/termux/proot-distro/releases/download/v4.6.0/$asset"
}
