#!/usr/bin/env python3
"""Regenerate FlClashColorData.kt from FlClash's own colour algorithm.

The android app does not depend on a colour-science library at runtime. This
script uses Google's materialyoucolor package to run the same HCT / Material
Color Utilities 2021 calculations as FlClash's prism_ui fork, applies the
FlClash overrides in plugins/prism_ui/lib/color.dart, and writes a compact
Kotlin table.

Requires:
    python -m pip install materialyoucolor

Reference snapshots used for this port:
    FlClash      afe7136002271fd64f098b154e7fff40be721eb8
    prism_ui     8f5fc22ca1a6bccb064589494ca69cedb38edb50

Run from the repository root:
    python tools/flclash_theme_gen.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from materialyoucolor.hct import Hct
from materialyoucolor.palettes.tonal_palette import TonalPalette
from materialyoucolor.scheme.scheme_content import SchemeContent
from materialyoucolor.scheme.scheme_tonal_spot import SchemeTonalSpot
from materialyoucolor.scheme.scheme_monochrome import SchemeMonochrome
from materialyoucolor.dynamiccolor.material_dynamic_colors import (
    MaterialDynamicColors as M,
)

# FlClash defaultPrimaryColors (lib/common/constant.dart).
PALETTES = [
    ("BLUE", "蓝色", 0xFF007AFF),
    ("MAGENTA", "品红", 0xFFCB30E0),
    ("ROSE", "玫红", 0xFFFF2D55),
    ("RED", "红色", 0xFFFF383C),
    ("ORANGE", "橙色", 0xFFFF8D28),
    ("YELLOW", "黄色", 0xFFFFCC00),
    ("GREEN", "绿色", 0xFF34C759),
    ("GRAPHITE", "石墨", 0xFF8E8E93),
]

# Order must match ThemeStyle in ThemeModels.kt.
STYLES = ["VIVID", "TINTED", "SOFT", "MONOCHROME"]

# Order must match ColorPalettes.kt.
ROLES = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "error", "onError", "errorContainer", "onErrorContainer",
    "background", "onBackground", "surface", "onSurface",
    "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant",
    "inverseSurface", "inverseOnSurface", "inversePrimary",
    "surfaceTint", "scrim",
    "surfaceDim", "surfaceBright", "surfaceContainerLowest",
    "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh",
    "surfaceContainerHighest",
]

PAPER_CHROMA = 1.5
SURFACE_CHROMA = 3.0
TINT_DIVISOR = 6.0
MAX_TINT_CHROMA = 10.0
PRIMARY_CONTAINER_CHROMA = 48.0


def argb(value) -> int:
    return value & 0xFFFFFFFF


def tone(palette, value: float) -> int:
    return argb(palette.get_hct(value).to_int())


def scheme_for(seed: int, dark: bool, style: str) -> dict[str, int]:
    source = Hct.from_int(seed)
    if style in ("VIVID", "TINTED"):
        base = SchemeContent(source, dark, 0.0, spec_version="2021")
    elif style == "SOFT":
        base = SchemeTonalSpot(source, dark, 0.0, spec_version="2021")
    else:
        base = SchemeMonochrome(source, dark, 0.0, spec_version="2021")

    primary_palette = base.primary_palette
    neutral_palette = base.neutral_palette
    neutral_variant = base.neutral_variant_palette

    def role(name: str) -> int:
        return argb(getattr(M, name).get_argb(base))

    # lib/providers/state/theme.dart _accent().
    if style in ("VIVID", "TINTED"):
        accent_tone = (
            min(75.0, max(50.0, source.tone + 3.0))
            if dark
            else min(60.0, max(35.0, source.tone))
        )
        primary = (
            seed
            if accent_tone == source.tone
            else argb(Hct.from_hct(source.hue, source.chroma, accent_tone).to_int())
        )
        on_primary = (
            0xFFFFFFFF
            if accent_tone <= 60.0
            else tone(
                TonalPalette.from_hue_and_chroma(source.hue, source.chroma),
                10,
            )
        )
    else:
        primary = role("primary")
        on_primary = role("onPrimary")

    # lib/providers/state/theme.dart primaryContainer override.
    container = TonalPalette.from_hue_and_chroma(
        primary_palette.hue,
        min(primary_palette.chroma, PRIMARY_CONTAINER_CHROMA),
    )
    primary_container = tone(container, 30.0 if dark else 90.0)
    on_primary_container = tone(container, 90.0 if dark else 30.0)

    tint = (
        min(source.chroma / TINT_DIVISOR, MAX_TINT_CHROMA)
        if style == "TINTED"
        else None
    )

    def neutral(role_name: str, max_chroma: float, dark_tone=None) -> int:
        palette = getattr(M, role_name).palette(base)
        chroma = tint if tint is not None else min(palette.chroma, max_chroma)
        value = (
            dark_tone
            if dark and dark_tone is not None
            else getattr(M, role_name).tone(base)
        )
        return tone(TonalPalette.from_hue_and_chroma(palette.hue, chroma), value)

    surface = neutral("surface", PAPER_CHROMA)
    surface_bright = neutral("surfaceBright", PAPER_CHROMA)
    surface_dim = neutral("surfaceDim", SURFACE_CHROMA)
    surface_lowest = neutral("surfaceContainerLowest", SURFACE_CHROMA)
    surface_low = neutral("surfaceContainerLow", SURFACE_CHROMA)
    surface_container = neutral("surfaceContainer", SURFACE_CHROMA, 15.0)
    surface_high = neutral("surfaceContainerHigh", SURFACE_CHROMA, 21.0)
    surface_highest = neutral("surfaceContainerHighest", SURFACE_CHROMA, 27.0)
    page = surface if dark else surface_container

    outline_variant_palette = M.outlineVariant.palette(base)
    outline_variant_chroma = (
        tint
        if tint is not None
        else min(outline_variant_palette.chroma, 4.0)
    )
    outline_variant = tone(
        TonalPalette.from_hue_and_chroma(
            outline_variant_palette.hue, outline_variant_chroma
        ),
        M.outlineVariant.tone(base),
    )

    on_surface = (
        argb(neutral_palette.get_hct(97.0).to_int())
        if dark
        else role("onSurface")
    )

    return {
        "primary": primary,
        "onPrimary": on_primary,
        "primaryContainer": primary_container,
        "onPrimaryContainer": on_primary_container,
        "secondary": role("secondary"),
        "onSecondary": role("onSecondary"),
        "secondaryContainer": role("secondaryContainer"),
        "onSecondaryContainer": role("onSecondaryContainer"),
        "tertiary": role("tertiary"),
        "onTertiary": role("onTertiary"),
        "tertiaryContainer": role("tertiaryContainer"),
        "onTertiaryContainer": role("onTertiaryContainer"),
        "error": role("error"),
        "onError": role("onError"),
        "errorContainer": role("errorContainer"),
        "onErrorContainer": role("onErrorContainer"),
        "background": page,
        "onBackground": on_surface,
        "surface": surface,
        "onSurface": on_surface,
        "surfaceVariant": surface_highest,
        "onSurfaceVariant": role("onSurfaceVariant"),
        "outline": role("outline"),
        "outlineVariant": outline_variant,
        "inverseSurface": role("inverseSurface"),
        "inverseOnSurface": role("inverseOnSurface"),
        "inversePrimary": role("inversePrimary"),
        "surfaceTint": primary,
        "scrim": 0xFF000000,
        "surfaceDim": surface_dim,
        "surfaceBright": surface_bright,
        "surfaceContainerLowest": surface_lowest,
        "surfaceContainerLow": surface_low,
        "surfaceContainer": surface_container,
        "surfaceContainerHigh": surface_high,
        "surfaceContainerHighest": surface_highest,
    }


def shift_tone(color: int, delta: float) -> int:
    hct = Hct.from_int(color)
    return argb(
        Hct.from_hct(
            hct.hue,
            hct.chroma,
            max(0.0, min(100.0, hct.tone + delta)),
        ).to_int()
    )


def pure_black(colors: dict[str, int]) -> dict[str, int]:
    """Mirror prism_ui/lib/color.dart ColorSchemeExtension.toPureBlack."""
    shift = Hct.from_int(colors["surface"]).tone
    result = {key: shift_tone(value, -shift) for key, value in colors.items()}
    result["surface"] = 0xFF000000
    result["surfaceDim"] = 0xFF000000
    result["surfaceContainerLowest"] = 0xFF000000
    result["background"] = 0xFF000000
    result["surfaceVariant"] = result["surfaceContainerHighest"]
    return result


def rows(dark: bool) -> list[list[int]]:
    result = []
    for _, _, seed in PALETTES:
        for style in STYLES:
            scheme = scheme_for(seed, dark, style)
            if dark:
                scheme = pure_black(scheme)
            result.append([scheme[role] for role in ROLES])
    return result


def emit_kotlin(path: Path) -> None:
    lines = [
        "package com.nemu.hibernate.ui.theme",
        "",
        "/*",
        " * GENERATED COLOR DATA - do not edit by hand.",
        " *",
        " * Source: FlClash (https://github.com/chen08209/FlClash),",
        " * lib/providers/state/theme.dart + plugins/prism_ui/lib/color.dart,",
        " * Apache-2.0. Exact FlClash vivid/tinted/soft/monochrome schemes for",
        " * its eight default iOS system colours. Dark roles apply FlClash's",
        " * toPureBlack() transform, so the page and window are true #000000.",
        " * Generator: tools/flclash_theme_gen.py",
        " */",
        "",
        "internal const val FLCLASH_ROLE_COUNT = 36",
        "",
        "internal val FLCLASH_LIGHT_ROLES: Array<LongArray> = arrayOf(",
    ]
    for values in rows(False):
        lines.append(
            "    longArrayOf("
            + ", ".join(f"0x{value:08X}" for value in values)
            + "),"
        )
    lines.extend([
        ")",
        "",
        "internal val FLCLASH_DARK_ROLES: Array<LongArray> = arrayOf(",
    ])
    for values in rows(True):
        lines.append(
            "    longArrayOf("
            + ", ".join(f"0x{value:08X}" for value in values)
            + "),"
        )
    lines.extend([")", ""])
    path.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    output = (
        root
        / "app/src/main/java/com/nemu/hibernate/ui/theme/FlClashColorData.kt"
    )
    emit_kotlin(output)
    print(f"wrote {output}")
