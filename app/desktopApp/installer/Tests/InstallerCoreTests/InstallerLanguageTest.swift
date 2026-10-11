import XCTest
@testable import InstallerCore

final class InstallerLanguageTest: XCTestCase {
    func testChineseLanguageVariants() {
        for code in ["zh", "zh-CN", "zh-Hans-CN", "zh-Hant-TW", "zh_Hans_CN"] {
            XCTAssertEqual(InstallerLanguage(preferredLanguages: [code]), .chinese)
        }
    }

    func testFirstSupportedPreferredLanguageWins() {
        XCTAssertEqual(InstallerLanguage(preferredLanguages: ["en-US", "zh-CN"]), .english)
        XCTAssertEqual(InstallerLanguage(preferredLanguages: ["fr-FR", "zh-CN", "en"]), .chinese)
    }

    func testUnknownOrEmptyLanguagesFallBackToEnglish() {
        XCTAssertEqual(InstallerLanguage(preferredLanguages: ["ja-JP"]), .english)
        XCTAssertEqual(InstallerLanguage(preferredLanguages: []), .english)
    }

    func testBothLanguagesHaveCompleteResourcesAndResolveNativeStrings() throws {
        let resources = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Resources")
        var previousKeys: Set<String>?
        for language in [InstallerLanguage.english, .chinese] {
            let directory = resources.appendingPathComponent("\(language.rawValue).lproj")
            let data = try Data(contentsOf: directory.appendingPathComponent("Localizable.strings"))
            let strings = try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: String])
            let bundle = try XCTUnwrap(Bundle(url: directory))
            XCTAssertGreaterThan(strings.count, 25)
            for (key, value) in strings {
                XCTAssertFalse(value.isEmpty)
                XCTAssertEqual(bundle.localizedString(forKey: key, value: nil, table: nil), value)
            }
            if let previousKeys { XCTAssertEqual(Set(strings.keys), previousKeys) }
            previousKeys = Set(strings.keys)
        }
    }
}
