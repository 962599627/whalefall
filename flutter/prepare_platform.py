"""Sync flutter/src to flutter/lib and configure macOS/iOS deployment targets & network permissions."""
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parent


def main():
    src = ROOT / "src"
    lib = ROOT / "lib"
    if src.exists():
        shutil.copytree(src, lib, dirs_exist_ok=True)

    widget_test = ROOT / "test" / "widget_test.dart"
    if widget_test.exists():
        widget_test.unlink()

    # macOS entitlements & deployment target (>= 11.0 for media_kit / CocoaPods)
    macos_runner = ROOT / "macos" / "Runner"
    if macos_runner.exists():
        for ent in macos_runner.glob("*.entitlements"):
            text = ent.read_text(encoding="utf-8")
            if "com.apple.security.network.client" not in text:
                text = text.replace(
                    "</dict>",
                    "  <key>com.apple.security.network.client</key>\n  <true/>\n</dict>",
                )
                ent.write_text(text, encoding="utf-8")
        info_plist = macos_runner / "Info.plist"
        if info_plist.exists():
            text = info_plist.read_text(encoding="utf-8")
            if "NSAppTransportSecurity" not in text:
                text = text.replace(
                    "</dict>",
                    "  <key>NSAppTransportSecurity</key>\n  <dict>\n    <key>NSAllowsArbitraryLoads</key>\n    <true/>\n  </dict>\n</dict>",
                    1,
                )
                info_plist.write_text(text, encoding="utf-8")

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

    # iOS Info.plist & deployment target (>= 13.0)
    ios_plist = ROOT / "ios" / "Runner" / "Info.plist"
    if ios_plist.exists():
        text = ios_plist.read_text(encoding="utf-8")
        if "NSAppTransportSecurity" not in text:
            text = text.replace(
                "</dict>",
                "  <key>NSAppTransportSecurity</key>\n  <dict>\n    <key>NSAllowsArbitraryLoads</key>\n    <true/>\n  </dict>\n</dict>",
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


if __name__ == "__main__":
    main()
