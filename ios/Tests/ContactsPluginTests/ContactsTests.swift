import Contacts
import XCTest

@testable import ContactsPlugin

class ContactsTests: XCTestCase {

    func testErrorCodesFollowUnifiedFormat() {
        XCTAssertEqual(ContactsError.unknown.code, "OS-PLUG-CONT-0000")
        XCTAssertEqual(ContactsError.invalidArgument.code, "OS-PLUG-CONT-0001")
        XCTAssertEqual(ContactsError.timeout.code, "OS-PLUG-CONT-0002")
        XCTAssertEqual(ContactsError.pendingOperation.code, "OS-PLUG-CONT-0003")
        XCTAssertEqual(ContactsError.ioError.code, "OS-PLUG-CONT-0004")
        XCTAssertEqual(ContactsError.notSupported.code, "OS-PLUG-CONT-0005")
        XCTAssertEqual(ContactsError.operationCancelled.code, "OS-PLUG-CONT-0006")
        XCTAssertEqual(ContactsError.permissionDenied.code, "OS-PLUG-CONT-0020")
    }

    func testContactToDictionaryMapsWireFormat() {
        let contact = CNMutableContact()
        contact.givenName = "Ada"
        contact.familyName = "Lovelace"
        contact.nickname = "Countess"
        contact.organizationName = "Analytical Engines"
        contact.jobTitle = "Mathematician"
        contact.phoneNumbers = [
            CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: "+351910000000"))
        ]
        contact.emailAddresses = [
            CNLabeledValue(label: CNLabelHome, value: "ada@example.com" as NSString)
        ]
        contact.birthday = DateComponents(year: 1815, month: 12, day: 10)

        let dict = Contacts().contactToDictionary(contact, desiredFields: [])

        XCTAssertNotNil(dict["id"] as? String)
        let name = dict["name"] as? [String: Any]
        XCTAssertEqual(name?["givenName"] as? String, "Ada")
        XCTAssertEqual(name?["familyName"] as? String, "Lovelace")
        XCTAssertEqual(dict["nickname"] as? String, "Countess")

        let phones = dict["phoneNumbers"] as? [[String: Any]]
        XCTAssertEqual(phones?.count, 1)
        XCTAssertEqual(phones?.first?["value"] as? String, "+351910000000")

        let emails = dict["emails"] as? [[String: Any]]
        XCTAssertEqual(emails?.first?["value"] as? String, "ada@example.com")

        let organizations = dict["organizations"] as? [[String: Any]]
        XCTAssertEqual(organizations?.first?["name"] as? String, "Analytical Engines")

        XCTAssertNotNil(dict["birthday"] as? Double)
    }

    func testContactToDictionaryHonorsDesiredFields() {
        let contact = CNMutableContact()
        contact.givenName = "Grace"
        contact.phoneNumbers = [
            CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: "+14085550100"))
        ]

        let dict = Contacts().contactToDictionary(contact, desiredFields: ["phoneNumbers"])

        XCTAssertNotNil(dict["id"] as? String)
        XCTAssertNotNil(dict["phoneNumbers"])
        XCTAssertNil(dict["name"])
        XCTAssertNil(dict["displayName"])
    }

    func testPickerPropertyKeyMapsFieldKinds() {
        let contacts = Contacts()
        XCTAssertEqual(contacts.pickerPropertyKey(forField: "phone"), CNContactPhoneNumbersKey)
        XCTAssertEqual(contacts.pickerPropertyKey(forField: "email"), CNContactEmailAddressesKey)
        XCTAssertEqual(contacts.pickerPropertyKey(forField: "address"), CNContactPostalAddressesKey)
        XCTAssertNil(contacts.pickerPropertyKey(forField: "phoneNumbers"))
    }

    func testPickedPropertyReturnsOnlyTheValueTypeAndName() {
        let contact = CNMutableContact()
        contact.givenName = "Ada"
        contact.familyName = "Lovelace"
        contact.emailAddresses = [CNLabeledValue(label: CNLabelHome, value: "ada@example.com" as NSString)]

        let dict = Contacts().pickedPropertyToDictionary(
            contact: contact,
            key: CNContactPhoneNumbersKey,
            label: CNLabelWork,
            value: CNPhoneNumber(stringValue: "+351920000000")
        )

        XCTAssertEqual(dict?["field"] as? String, "phone")
        XCTAssertEqual(dict?["value"] as? String, "+351920000000")
        XCTAssertEqual(dict?["type"] as? String, "work")
        XCTAssertEqual(dict?["displayName"] as? String, "Ada Lovelace")
        XCTAssertEqual(Set(dict?.keys.map { $0 } ?? []), ["field", "value", "type", "displayName"])
    }

    func testPickedPropertyMapsAddressAndRejectsMismatchedValue() {
        let contact = CNMutableContact()
        let postal = CNMutablePostalAddress()
        postal.street = "1 Main St"
        postal.city = "Lisbon"

        let contacts = Contacts()
        let dict = contacts.pickedPropertyToDictionary(
            contact: contact, key: CNContactPostalAddressesKey, label: CNLabelHome, value: postal
        )
        XCTAssertEqual(dict?["field"] as? String, "address")
        XCTAssertTrue((dict?["value"] as? String)?.contains("1 Main St") ?? false)
        XCTAssertNil(dict?["displayName"])
        let address = dict?["address"] as? [String: String]
        XCTAssertEqual(address?["streetAddress"], "1 Main St")
        XCTAssertEqual(address?["locality"], "Lisbon")

        XCTAssertNil(contacts.pickedPropertyToDictionary(
            contact: contact, key: CNContactPostalAddressesKey, label: nil, value: "not an address"
        ))
    }
}
