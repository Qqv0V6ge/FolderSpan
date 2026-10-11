import Foundation
import UniformTypeIdentifiers
import XCTest
@testable import ExternalTextImport

final class ExternalTextProviderTests: XCTestCase {
    func testPreservesText() {
        let text = "  文本\nhttps://example.com/a  "
        let provider = NSItemProvider(object: text as NSString)
        XCTAssertEqual(ExternalTextProvider.typeIdentifier(for: provider), UTType.plainText.identifier)
        assertLoaded(provider, equals: text)
    }

    func testLoadsWebURL() {
        let provider = NSItemProvider(object: NSURL(string: "https://example.com/a")!)
        provider.suggestedName = "remote.txt"
        XCTAssertEqual(ExternalTextProvider.typeIdentifier(for: provider), UTType.url.identifier)
        assertLoaded(provider, equals: "https://example.com/a")
    }

    func testIgnoresBlankText() {
        assertLoaded(NSItemProvider(object: " \n " as NSString), equals: nil)
    }

    func testFileRepresentationTakesPriority() {
        let provider = NSItemProvider(object: "description" as NSString)
        provider.registerDataRepresentation(forTypeIdentifier: UTType.fileURL.identifier, visibility: .all) { completion in
            completion(Data("file:///tmp/example.txt".utf8), nil)
            return nil
        }
        XCTAssertNil(ExternalTextProvider.typeIdentifier(for: provider))
    }

    func testNamedTextFileKeepsFileImport() {
        let provider = NSItemProvider(object: "file contents" as NSString)
        provider.suggestedName = "notes.txt"
        XCTAssertNil(ExternalTextProvider.typeIdentifier(for: provider))
    }

    func testReadsUtf8Data() {
        let provider = NSItemProvider()
        provider.registerDataRepresentation(forTypeIdentifier: UTType.utf8PlainText.identifier, visibility: .all) { completion in
            completion(Data("拖入文本".utf8), nil)
            return nil
        }
        assertLoaded(provider, equals: "拖入文本")
    }

    private func assertLoaded(_ provider: NSItemProvider, equals expected: String?) {
        let completed = expectation(description: "Provider loaded")
        ExternalTextProvider.load(provider) { text in
            XCTAssertEqual(text, expected)
            completed.fulfill()
        }
        wait(for: [completed], timeout: 5)
    }
}
