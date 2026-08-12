package com.nexterm.data.database

/**
 * Content shipped on first launch so the app is useful immediately and no screen is
 * ever blank for lack of data. Inserted with IGNORE semantics, so a user who edits
 * or deletes a default never has it silently reinstated.
 */
object DefaultContent {

    fun quickCommands(): List<QuickCommandEntity> = listOf(
        QuickCommandEntity(label = "Update packages", command = "pkg update && pkg upgrade", icon = "Refresh", position = 0),
        QuickCommandEntity(label = "APT update", command = "apt update && apt upgrade", icon = "Refresh", position = 1),
        QuickCommandEntity(label = "Downloads", command = "cd ~/storage/downloads", icon = "Folder", position = 2),
        QuickCommandEntity(label = "Git status", command = "git status", icon = "Difference", position = 3),
        QuickCommandEntity(label = "Git pull", command = "git pull", icon = "Download", position = 4),
        QuickCommandEntity(label = "Gradle release", command = "./gradlew assembleRelease", icon = "Build", position = 5),
        QuickCommandEntity(label = "Disk usage", command = "du -sh * | sort -h", icon = "Storage", position = 6),
        QuickCommandEntity(label = "Processes", command = "top", icon = "Monitor", position = 7),
    )

    fun snippets(): List<SnippetEntity> = listOf(
        SnippetEntity(
            name = "Git commit",
            template = "git add . && git commit -m \"\$MESSAGE\"",
            description = "Stage everything and commit with a message",
            position = 0,
        ),
        SnippetEntity(
            name = "Gradle build",
            template = "./gradlew assembleRelease",
            description = "Build a release APK",
            position = 1,
        ),
        SnippetEntity(
            name = "Python server",
            template = "python -m http.server \$PORT",
            description = "Serve the current directory over HTTP",
            position = 2,
        ),
        SnippetEntity(
            name = "Find file",
            template = "find . -name \"\$PATTERN\"",
            description = "Search the current tree by name",
            position = 3,
        ),
        SnippetEntity(
            name = "Tail log",
            template = "tail -f \$FILE",
            description = "Follow a file as it grows",
            position = 4,
        ),
        SnippetEntity(
            name = "SSH connect",
            template = "ssh \$USER@\$HOST",
            description = "Open a remote shell",
            position = 5,
        ),
    )

    /** The default keyboard toolbar from spec §24. */
    fun toolbarKeys(): List<ToolbarKeyEntity> = listOf(
        "ESC" to "ESC",
        "TAB" to "TAB",
        "CTRL" to "CTRL",
        "ALT" to "ALT",
        "↑" to "ARROW_UP",
        "↓" to "ARROW_DOWN",
        "←" to "ARROW_LEFT",
        "→" to "ARROW_RIGHT",
        "HOME" to "HOME",
        "END" to "END",
        "PGUP" to "PAGE_UP",
        "PGDN" to "PAGE_DOWN",
    ).mapIndexed { index, (label, action) ->
        ToolbarKeyEntity(label = label, action = action, position = index)
    } + listOf("/", "-", "|", "&", "$").mapIndexed { index, ch ->
        ToolbarKeyEntity(label = ch, action = "LITERAL", literal = ch, position = 12 + index)
    }

    /**
     * Ids and versions must match `DistroCatalog`, which is the source of truth for
     * what can actually be downloaded. These rows only carry install *state*; a
     * catalog entry with no row here still appears, defaulted to NOT_INSTALLED.
     */
    fun distros(): List<DistroEntity> = listOf(
        DistroEntity(id = "alpine", displayName = "Alpine Linux", version = "edge", status = "NOT_INSTALLED"),
        DistroEntity(id = "debian", displayName = "Debian", version = "12 (bookworm)", status = "NOT_INSTALLED"),
        DistroEntity(id = "ubuntu", displayName = "Ubuntu", version = "23.10 (mantic)", status = "NOT_INSTALLED"),
        DistroEntity(id = "archlinux", displayName = "Arch Linux", version = "rolling", status = "NOT_INSTALLED"),
        DistroEntity(id = "fedora", displayName = "Fedora", version = "39", status = "NOT_INSTALLED"),
        DistroEntity(id = "void", displayName = "Void Linux", version = "rolling", status = "NOT_INSTALLED"),
    )

    /**
     * Built-in colour schemes (spec §25). Each supplies the 16 ANSI colours plus the
     * interface accents; xterm-256 indices 16..255 are derived arithmetically.
     */
    fun themes(): List<ThemeEntity> = listOf(
        theme(
            name = "NEXTERM Neon",
            background = 0xFF07090F, foreground = 0xFFD8E0F0, cursor = 0xFF32E6C8,
            selection = 0x5532E6C8, accent = 0xFF32E6C8,
            ansi = listOf(
                0xFF12161F, 0xFFFF5C7A, 0xFF3DDC97, 0xFFFFC857,
                0xFF4D9DE0, 0xFF9D7CFF, 0xFF32E6C8, 0xFFB6C2D9,
                0xFF3A4256, 0xFFFF8FA3, 0xFF7BE8B8, 0xFFFFD98A,
                0xFF7FBEEA, 0xFFBFA5FF, 0xFF7FF0DC, 0xFFEAF0FA,
            ),
        ),
        theme(
            name = "Dracula",
            background = 0xFF282A36, foreground = 0xFFF8F8F2, cursor = 0xFFF8F8F2,
            selection = 0x5544475A, accent = 0xFFBD93F9,
            ansi = listOf(
                0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
                0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
                0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
                0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
            ),
        ),
        theme(
            name = "Nord",
            background = 0xFF2E3440, foreground = 0xFFD8DEE9, cursor = 0xFFD8DEE9,
            selection = 0x554C566A, accent = 0xFF88C0D0,
            ansi = listOf(
                0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
                0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
            ),
        ),
        theme(
            name = "One Dark",
            background = 0xFF282C34, foreground = 0xFFABB2BF, cursor = 0xFF528BFF,
            selection = 0x553E4451, accent = 0xFF61AFEF,
            ansi = listOf(
                0xFF282C34, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
                0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
                0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
                0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFFFFFFF,
            ),
        ),
        theme(
            name = "Solarized Dark",
            background = 0xFF002B36, foreground = 0xFF839496, cursor = 0xFF93A1A1,
            selection = 0x55073642, accent = 0xFF268BD2,
            ansi = listOf(
                0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
            ),
        ),
        theme(
            name = "Monokai",
            background = 0xFF272822, foreground = 0xFFF8F8F2, cursor = 0xFFF8F8F0,
            selection = 0x5549483E, accent = 0xFFA6E22E,
            ansi = listOf(
                0xFF272822, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75,
                0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF8F8F2,
                0xFF75715E, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75,
                0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF9F8F5,
            ),
        ),
        theme(
            name = "Tokyo Night",
            background = 0xFF1A1B26, foreground = 0xFFC0CAF5, cursor = 0xFFC0CAF5,
            selection = 0x5533467C, accent = 0xFF7AA2F7,
            ansi = listOf(
                0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
                0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
            ),
        ),
        theme(
            name = "Catppuccin Mocha",
            background = 0xFF1E1E2E, foreground = 0xFFCDD6F4, cursor = 0xFFF5E0DC,
            selection = 0x55585B70, accent = 0xFFCBA6F7,
            ansi = listOf(
                0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
                0xFF585B70, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8,
            ),
        ),
        theme(
            name = "Cyberpunk",
            background = 0xFF0B0C1E, foreground = 0xFFE7F6FF, cursor = 0xFFFF2E97,
            selection = 0x55FF2E97, accent = 0xFFFF2E97,
            ansi = listOf(
                0xFF16182E, 0xFFFF2E97, 0xFF00F0A0, 0xFFFFE347,
                0xFF00B8FF, 0xFFC44BFF, 0xFF00F0FF, 0xFFCFE3F2,
                0xFF3A3D5C, 0xFFFF6BB8, 0xFF5CFFC4, 0xFFFFF07A,
                0xFF6BD5FF, 0xFFDA8CFF, 0xFF7DFFFF, 0xFFFFFFFF,
            ),
        ),
        theme(
            name = "Matrix",
            background = 0xFF000000, foreground = 0xFF00FF41, cursor = 0xFF00FF41,
            selection = 0x5500FF41, accent = 0xFF00FF41,
            ansi = listOf(
                0xFF0A0A0A, 0xFF00A32E, 0xFF00FF41, 0xFF00C853,
                0xFF008F28, 0xFF00E676, 0xFF00D68A, 0xFF9CFFB0,
                0xFF1B1B1B, 0xFF00C853, 0xFF5CFF8F, 0xFF00E676,
                0xFF00B248, 0xFF69F0AE, 0xFF64FFDA, 0xFFE8FFEF,
            ),
        ),
    )

    private fun theme(
        name: String,
        background: Long,
        foreground: Long,
        cursor: Long,
        selection: Long,
        accent: Long,
        ansi: List<Long>,
    ) = ThemeEntity(
        name = name,
        isBuiltIn = true,
        background = background,
        foreground = foreground,
        cursor = cursor,
        selection = selection,
        accent = accent,
        ansi = ansi,
    )
}
