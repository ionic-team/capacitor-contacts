import Contacts
import Foundation

/// Business logic for reading, searching, saving and removing device
/// contacts via the Contacts framework (`CNContactStore`). Holds no
/// bridge-framework types; the bridge plugin parses calls and delegates here.
@objc public class Contacts: NSObject {

    private let store = CNContactStore()

    /// Marker value used by the legacy API to request every field.
    private let allFields = "*"

    // MARK: - Authorization

    /// `true` for full access, or for iOS 18+ Limited Access (`.limited`),
    /// which the store serves transparently against the user-selected subset
    /// for both reads and writes.
    @objc public func isAuthorized() -> Bool {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        if status == .authorized { return true }
        if #available(iOS 18.0, *), status == .limited { return true }
        return false
    }

    /// Requests contacts access. The completion receives whether access was
    /// granted; on iOS 18+ choosing "Select Contacts…" (Limited Access)
    /// completes with `true`.
    @objc public func requestAccess(completion: @escaping (Bool) -> Void) {
        store.requestAccess(for: .contacts) { granted, _ in
            completion(granted || self.isAuthorized())
        }
    }

    // MARK: - Keys

    /// Keys fetched from the store. Image data is by far the heaviest key,
    /// so bulk searches fetch only the thumbnail; single-contact fetches add
    /// the full image as a fallback for contacts without a generated thumbnail.
    private func fetchKeys(includePhotos: Bool, fullPhotoData: Bool = false) -> [CNKeyDescriptor] {
        var stringKeys = [
            CNContactIdentifierKey,
            CNContactGivenNameKey,
            CNContactFamilyNameKey,
            CNContactMiddleNameKey,
            CNContactNamePrefixKey,
            CNContactNameSuffixKey,
            CNContactNicknameKey,
            CNContactOrganizationNameKey,
            CNContactDepartmentNameKey,
            CNContactJobTitleKey,
            CNContactPhoneNumbersKey,
            CNContactEmailAddressesKey,
            CNContactPostalAddressesKey,
            CNContactUrlAddressesKey,
            CNContactInstantMessageAddressesKey,
            CNContactBirthdayKey
            // NOTE: CNContactNoteKey is intentionally omitted. Since iOS 13 it
            // requires the restricted `com.apple.developer.contacts.notes`
            // entitlement (granted by Apple on request); requesting it without
            // the entitlement makes every fetch throw. `note` is therefore
            // not supported on iOS by default; see README.
        ]
        if includePhotos {
            stringKeys.append(CNContactThumbnailImageDataKey)
            if fullPhotoData {
                stringKeys.append(CNContactImageDataKey)
            }
        }
        var keys = stringKeys.map { $0 as CNKeyDescriptor }
        keys.append(CNContactFormatter.descriptorForRequiredKeys(for: .fullName))
        return keys
    }

    // MARK: - Search

    /// Searches contacts and returns an array of wire-format dictionaries.
    /// Under iOS 18+ Limited Access the store enumerates only the contacts
    /// the user shared with the app.
    ///
    /// - Parameters:
    ///   - fields: fields to search against (`["*"]` for all).
    ///   - filter: case-insensitive substring; empty returns all.
    ///   - multiple: when false, at most one contact is returned.
    ///   - desiredFields: if non-empty, limits the fields populated on results.
    ///   - hasPhoneNumber: when true, only contacts with a phone number are kept.
    public func search(
        fields: [String],
        filter: String,
        multiple: Bool,
        desiredFields: [String],
        hasPhoneNumber: Bool
    ) throws -> [[String: Any]] {
        let desired = Set(desiredFields)
        let wantsPhotos = desired.isEmpty || desired.contains("photos")
        let request = CNContactFetchRequest(keysToFetch: fetchKeys(includePhotos: wantsPhotos))
        let needle = filter.lowercased()

        var results: [[String: Any]] = []

        do {
            try store.enumerateContacts(with: request) { contact, stopPointer in
                if hasPhoneNumber && contact.phoneNumbers.isEmpty { return }

                if !filter.isEmpty && !self.contactMatches(contact, fields: fields, needle: needle) {
                    return
                }

                results.append(self.contactToDictionary(contact, desiredFields: desired))

                if !multiple {
                    stopPointer.pointee = true
                }
            }
        } catch {
            throw mapStoreError(error)
        }

        return results
    }

    /// `true` when any of the requested `fields` of `contact` contains
    /// `needle` (case-insensitive). `["*"]` searches every field, mirroring
    /// the legacy field-restricted search semantics.
    private func contactMatches(_ contact: CNContact, fields: [String], needle: String) -> Bool {
        let searchAll = fields.contains(allFields)
        let requested = Set(fields)
        func wants(_ candidates: String...) -> Bool {
            return searchAll || !requested.isDisjoint(with: candidates)
        }

        if wants("id") && contact.identifier.lowercased() == needle {
            return true
        }

        var haystacks: [String] = []
        if wants("displayName", "name", "formatted") {
            haystacks.append(CNContactFormatter.string(from: contact, style: .fullName) ?? "")
        }
        if wants("name", "givenName") { haystacks.append(contact.givenName) }
        if wants("name", "familyName") { haystacks.append(contact.familyName) }
        if wants("name", "middleName") { haystacks.append(contact.middleName) }
        if wants("name", "honorificPrefix") { haystacks.append(contact.namePrefix) }
        if wants("name", "honorificSuffix") { haystacks.append(contact.nameSuffix) }
        if wants("nickname") { haystacks.append(contact.nickname) }
        if wants("organizations", "department", "title") {
            haystacks.append(contentsOf: [contact.organizationName, contact.departmentName, contact.jobTitle])
        }
        if wants("phoneNumbers") {
            haystacks.append(contentsOf: contact.phoneNumbers.map { $0.value.stringValue })
        }
        if wants("emails") {
            haystacks.append(contentsOf: contact.emailAddresses.map { $0.value as String })
        }
        if wants("urls") {
            haystacks.append(contentsOf: contact.urlAddresses.map { $0.value as String })
        }
        if wants("ims") {
            haystacks.append(contentsOf: contact.instantMessageAddresses.map { $0.value.username })
        }
        if wants("addresses", "streetAddress", "locality", "region", "postalCode", "country") {
            for labeled in contact.postalAddresses {
                let postal = labeled.value
                haystacks.append(contentsOf: [postal.street, postal.city, postal.state, postal.postalCode, postal.country])
            }
        }

        return haystacks.contains { $0.lowercased().contains(needle) }
    }

    // MARK: - Read single

    /// Returns the wire-format dictionary for the contact identified by
    /// `identifier`, or nil if not found (or outside the Limited Access set).
    public func getContact(byId identifier: String, desiredFields: [String] = []) -> [String: Any]? {
        do {
            let contact = try store.unifiedContact(withIdentifier: identifier, keysToFetch: fetchKeys(includePhotos: true, fullPhotoData: true))
            return contactToDictionary(contact, desiredFields: Set(desiredFields))
        } catch {
            return nil
        }
    }

    // MARK: - Save (insert or update)

    /// Inserts a new contact or updates the existing one identified by
    /// `contact["id"]`. Returns the saved contact's wire-format dictionary.
    ///
    /// Under iOS 18+ Limited Access, inserted contacts join the app's
    /// accessible set automatically; updating a contact outside that set
    /// throws `.invalidArgument` (the store cannot see it).
    public func save(contact dict: [String: Any]) throws -> [String: Any] {
        let saveRequest = CNSaveRequest()
        let identifier = (dict["id"] as? String)?.isEmpty == false ? dict["id"] as? String : nil

        let mutable: CNMutableContact
        if let identifier = identifier {
            let keys = fetchKeys(includePhotos: true, fullPhotoData: true)
            guard let existing = try? store.unifiedContact(withIdentifier: identifier, keysToFetch: keys),
                  let copy = existing.mutableCopy() as? CNMutableContact else {
                throw ContactsError.invalidArgument
            }
            mutable = copy
            apply(dict, to: mutable)
            saveRequest.update(mutable)
        } else {
            mutable = CNMutableContact()
            apply(dict, to: mutable)
            saveRequest.add(mutable, toContainerWithIdentifier: nil)
        }

        do {
            try store.execute(saveRequest)
        } catch {
            throw mapStoreError(error)
        }

        guard let saved = getContact(byId: mutable.identifier) else {
            throw ContactsError.unknown
        }
        return saved
    }

    // MARK: - Remove

    /// Removes the contact identified by `identifier`. Throws
    /// `.invalidArgument` when no accessible contact has that id.
    public func remove(id identifier: String) throws {
        guard let contact = try? store.unifiedContact(withIdentifier: identifier, keysToFetch: [CNContactIdentifierKey as CNKeyDescriptor]),
              let mutable = contact.mutableCopy() as? CNMutableContact else {
            throw ContactsError.invalidArgument
        }
        let request = CNSaveRequest()
        request.delete(mutable)
        do {
            try store.execute(request)
        } catch {
            throw mapStoreError(error)
        }
    }

    // MARK: - Error mapping

    /// Maps Contacts-framework errors onto the unified error codes.
    private func mapStoreError(_ error: Error) -> ContactsError {
        let nsError = error as NSError
        guard nsError.domain == CNErrorDomain else { return .ioError }
        switch nsError.code {
        case CNError.Code.authorizationDenied.rawValue:
            return .permissionDenied
        case CNError.Code.recordDoesNotExist.rawValue:
            return .invalidArgument
        default:
            return .ioError
        }
    }
}
