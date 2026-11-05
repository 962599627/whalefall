"""Sync flutter/src to flutter/lib, bundle brand logo assets, and configure Apple/Windows native shells."""
from pathlib import Path
import re
import shutil
import struct
import subprocess
ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parent
LOGO_MARK = REPO_ROOT / "android" / "src" / "main" / "res" / "drawable-nodpi" / "logo_mark.png"
OPAQUE_ICON = REPO_ROOT / "docs" / "images" / "app-icon-512.png"

def _png_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = struct.unpack(">II", data[16:24])
        if w > 0 and h > 0:
            return w, h
    return 1024, 1024

def _replace_xcassets_icons(appiconset_dir: Path, source_png: Path) -> None:
    if not appiconset_dir.exists() or not source_png.exists():
        return
    sips = shutil.which("sips")
    for png_file in appiconset_dir.glob("*.png"):
        w, h = _png_dimensions(png_file)
        shutil.copy2(source_png, png_file)
        if sips:
            subprocess.run(
                [sips, "-z", str(h), str(w), str(png_file)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )

def _write_windows_ico(ico_path: Path, source_png: Path) -> None:
    if not ico_path.parent.exists() or not source_png.exists():
        return
    png_bytes = source_png.read_bytes()
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack(
        "<BBBBHHII",
        0,  # 256px width
        0,  # 256px height
        0,
        0,
        1,
        32,
        len(png_bytes),
        22,
    )
    ico_path.write_bytes(header + entry + png_bytes)

def main() -> None:
    src = ROOT / "src"
    lib = ROOT / "lib"
    if src.exists():
        shutil.copytree(src, lib, dirs_exist_ok=True)
    # Bundle user's brand logo into flutter/assets/logo_mark.png
    assets_dir = ROOT / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)
    if LOGO_MARK.exists():
        shutil.copy2(LOGO_MARK, assets_dir / "logo_mark.png")
    widget_test = ROOT / "test" / "widget_test.dart"
    if widget_test.exists():
        widget_test.unlink()
    # macOS entitlements, deployment target, app icon, and native dark full-size window
    macos_runner = ROOT / "macos" / "Runner"
    if macos_runner.exists():
        _replace_xcassets_icons(
            macos_runner / "Assets.xcassets" / "AppIcon.appiconset",
            LOGO_MARK if LOGO_MARK.exists() else OPAQUE_ICON,
        )
        for ent in macos_runner.glob("*.entitlements"):
            text = ent.read_text(encoding="utf-8")
            if "com.apple.security.network.client" not in text:
                text = text.replace(
                    "</dict>",
                    "  <key>com.apple.security.network.client</key>\n  <true/>\n</dict>",
                )
            # Offline downloads are saved to ~/Downloads/Marco Movie
            if "com.apple.security.files.downloads.read-write" not in text:
                text = text.replace(
                    "</dict>",
                    "  <key>com.apple.security.files.downloads.read-write</key>\n  <true/>\n</dict>",
                )
            ent.write_text(text, encoding="utf-8")
        info_plist = macos_runner / "Info.plist"
        if info_plist.exists():
            text = info_plist.read_text(encoding="utf-8")
            text = text.replace("<string>marco_movie</string>", "<string>Marco Movie</string>")
            if "NSAppTransportSecurity" not in text:
                text = text.replace(
                    "</dict>",
                    "  <key>NSAppTransportSecurity</key>\n  <dict>\n    <key>NSAllowsArbitraryLoads</key>\n    <true/>\n  </dict>\n</dict>",
                    1,
                )
            info_plist.write_text(text, encoding="utf-8")
        window_swift = macos_runner / "MainFlutterWindow.swift"
        if window_swift.exists():
            swift_text = window_swift.read_text(encoding="utf-8")
            if "titlebarAppearsTransparent" not in swift_text:
                swift_text = swift_text.replace(
                    "super.awakeFromNib()",
                    "self.titleVisibility = .hidden\n"
                    "    self.titlebarAppearsTransparent = true\n"
                    "    self.styleMask.insert(.fullSizeContentView)\n"
                    "    self.appearance = NSAppearance(named: .darkAqua)\n"
                    "    self.minSize = NSSize(width: 900, height: 600)\n"
                    "    super.awakeFromNib()",
                )
                window_swift.write_text(swift_text, encoding="utf-8")
    macos_podfile = ROOT / "macos" / "Podfile"
    if macos_podfile.exists():
        text = macos_podfile.read_text(encoding="utf-8")
        text = re.sub(
            r"#?\s*platform\s+:osx\s*,\s*'[^']+'",
            "platform :osx, '11.0'",
            text,
        )
        macos_podfile.write_text(text, encoding="utf-8")
    macos_pbx = ROOT / "macos" / "Runner.xcodeproj" / "project.pbxproj"
    if macos_pbx.exists():
        text = macos_pbx.read_text(encoding="utf-8")
        text = re.sub(
            r"MACOSX_DEPLOYMENT_TARGET\s*=\s*10\.\d+;",
            "MACOSX_DEPLOYMENT_TARGET = 11.0;",
            text,
        )
        macos_pbx.write_text(text, encoding="utf-8")
    # iOS Info.plist, deployment target, and opaque AppIcon set
    ios_runner = ROOT / "ios" / "Runner"
    if ios_runner.exists():
        _replace_xcassets_icons(
            ios_runner / "Assets.xcassets" / "AppIcon.appiconset",
            OPAQUE_ICON if OPAQUE_ICON.exists() else LOGO_MARK,
        )
        ios_plist = ios_runner / "Info.plist"
        if ios_plist.exists():
            text = ios_plist.read_text(encoding="utf-8")
            text = text.replace("<string>Marco Movie</string>", "<string>Marco Movie</string>")
            text = text.replace("<string>marco_movie</string>", "<string>Marco Movie</string>")
            if "NSAppTransportSecurity" not in text:
                text = text.replace(
                    "</dict>",
                    "  <key>UIStatusBarStyle</key>\n"
                    "  <string>UIStatusBarStyleLightContent</string>\n"
                    "  <key>UIViewControllerBasedStatusBarAppearance</key>\n"
                    "  <false/>\n"
                    "  <key>NSAppTransportSecurity</key>\n"
                    "  <dict>\n"
                    "    <key>NSAllowsArbitraryLoads</key>\n"
                    "    <true/>\n"
                    "  </dict>\n"
                    "</dict>",
                    1,
                )
            # Expose offline downloads (Documents/Marco Movie) in the Files app
            if "UIFileSharingEnabled" not in text:
                text = text.replace(
                    "</dict>\n</plist>",
                    "  <key>UIFileSharingEnabled</key>\n"
                    "  <true/>\n"
                    "  <key>LSSupportsOpeningDocumentsInPlace</key>\n"
                    "  <true/>\n"
                    "</dict>\n</plist>",
                    1,
                )
            ios_plist.write_text(text, encoding="utf-8")
    ios_podfile = ROOT / "ios" / "Podfile"
    if ios_podfile.exists():
        text = ios_podfile.read_text(encoding="utf-8")
        text = re.sub(
            r"#?\s*platform\s+:ios\s*,\s*'[^']+'",
            "platform :ios, '13.0'",
            text,
        )
        ios_podfile.write_text(text, encoding="utf-8")
    # Windows icon & title
    win_ico = ROOT / "windows" / "runner" / "resources" / "app_icon.ico"
    if win_ico.parent.exists():
        _write_windows_ico(
            win_ico,
            OPAQUE_ICON if OPAQUE_ICON.exists() else LOGO_MARK,
        )

if __name__ == "__main__":
    main()
