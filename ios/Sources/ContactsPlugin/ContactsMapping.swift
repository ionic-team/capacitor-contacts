import Contacts
import Foundation

/// Wire-format mapping between `CNContact` and the plugin's W3C-shaped
/// dictionaries, shared by every method result and `save` input.
extension Contacts {

    // MARK: - Mapping CNContact -> dictionary

    private func wants(_ field: String, _ desired: Set<String>) -> Bool {
        return desired.isEmpty || desired.contains(field)
    }

    /// Maps a `CNContact` to the wire format. Every property is guarded with
    /// `isKeyAvailable` so partially-fetched contacts (e.g. returned by the
    /// permissionless system picker) never raise
    /// `CNContactPropertyNotFetchedException`.
    func contactToDictionary(_ contact: CNContact, desiredFields desired: Set<String>) -> [String: Any] {
        var dict: [String: Any] = [:]
        dict["id"] = contact.identifier

        let formattedName = contact.areKeysAvailable([CNContactFormatter.descriptorForRequiredKeys(for: .fullName)])
            ? (CNContactFormatter.string(from: contact, style: .fullName) ?? "")
            : ""

        if wants("displayName", desired) {
            dict["displayName"] = formattedName
        }

        if wants("name", desired) {
            var name: [String: Any] = [:]
            name["formatted"] = formattedName
            if contact.isKeyAvailable(CNContactFamilyNameKey) { name["familyName"] = contact.familyName }
            if contact.isKeyAvailable(CNContactGivenNameKey) { name["givenName"] = contact.givenName }
            if contact.isKeyAvailable(CNContactMiddleNameKey) { name["middleName"] = contact.middleName }
            if contact.isKeyAvailable(CNContactNamePrefixKey) { name["honorificPrefix"] = contact.namePrefix }
            if contact.isKeyAvailable(CNContactNameSuffixKey) { name["honorificSuffix"] = contact.nameSuffix }
            dict["name"] = name
        }

        if wants("nickname", desired) && contact.isKeyAvailable(CNContactNicknameKey) && !contact.nickname.isEmpty {
            dict["nickname"] = contact.nickname
        }

        if wants("phoneNumbers", desired) && contact.isKeyAvailable(CNContactPhoneNumbersKey) && !contact.phoneNumbers.isEmpty {
            dict["phoneNumbers"] = contact.phoneNumbers.map { labeled in
                field(
                    id: labeled.identifier,
                    value: labeled.value.stringValue,
                    type: canonicalLabel(labeled.label)
                )
            }
        }

        if wants("emails", desired) && contact.isKeyAvailable(CNContactEmailAddressesKey) && !contact.emailAddresses.isEmpty {
            dict["emails"] = contact.emailAddresses.map { labeled in
                field(
                    id: labeled.identifier,
                    value: labeled.value as String,
                    type: canonicalLabel(labeled.label)
                )
            }
        }

        if wants("urls", desired) && contact.isKeyAvailable(CNContactUrlAddressesKey) && !contact.urlAddresses.isEmpty {
            dict["urls"] = contact.urlAddresses.map { labeled in
                field(
                    id: labeled.identifier,
                    value: labeled.value as String,
                    type: canonicalLabel(labeled.label)
                )
            }
        }

        if wants("ims", desired) && contact.isKeyAvailable(CNContactInstantMessageAddressesKey) && !contact.instantMessageAddresses.isEmpty {
            dict["ims"] = contact.instantMessageAddresses.map { labeled in
                field(
                    id: labeled.identifier,
                    value: labeled.value.username,
                    type: labeled.value.service.lowercased()
                )
            }
        }

        if wants("addresses", desired) && contact.isKeyAvailable(CNContactPostalAddressesKey) && !contact.postalAddresses.isEmpty {
            dict["addresses"] = contact.postalAddresses.map(addressDictionary)
        }

        let hasOrgKeys = contact.isKeyAvailable(CNContactOrganizationNameKey) &&
            contact.isKeyAvailable(CNContactDepartmentNameKey) &&
            contact.isKeyAvailable(CNContactJobTitleKey)
        if wants("organizations", desired) && hasOrgKeys &&
            (!contact.organizationName.isEmpty || !contact.departmentName.isEmpty || !contact.jobTitle.isEmpty) {
            dict["organizations"] = [[
                "pref": false,
                "type": "work",
                "name": contact.organizationName,
                "department": contact.departmentName,
                "title": contact.jobTitle
            ]]
        }

        if wants("birthday", desired), contact.isKeyAvailable(CNContactBirthdayKey), var birthday = contact.birthday {
            if birthday.year == nil { birthday.year = 1970 }
            if let date = Calendar.current.date(from: birthday) {
                dict["birthday"] = date.timeIntervalSince1970 * 1000.0
            }
        }

        // `note` requires the restricted contacts-notes entitlement on iOS; only
        // read it when it was actually fetched (i.e. the entitlement is present
        // and the key was added to `fetchKeys`).
        if wants("note", desired) && contact.isKeyAvailable(CNContactNoteKey) && !contact.note.isEmpty {
            dict["note"] = contact.note
        }

        if wants("photos", desired) {
            let thumbnail = contact.isKeyAvailable(CNContactThumbnailImageDataKey) ? contact.thumbnailImageData : nil
            let full = contact.isKeyAvailable(CNContactImageDataKey) ? contact.imageData : nil
            if let imageData = thumbnail ?? full {
                dict["photos"] = [field(id: nil, value: imageData.base64EncodedString(), type: "base64")]
            }
        }

        return dict
    }

    private func addressDictionary(_ labeled: CNLabeledValue<CNPostalAddress>) -> [String: Any] {
        let postal = labeled.value
        var address: [String: Any] = [:]
        address["id"] = labeled.identifier
        address["pref"] = false
        address["type"] = canonicalLabel(labeled.label)
        address["formatted"] = CNPostalAddressFormatter.string(from: postal, style: .mailingAddress)
        address["streetAddress"] = postal.street
        address["locality"] = postal.city
        address["region"] = postal.state
        address["postalCode"] = postal.postalCode
        address["country"] = postal.country
        return address
    }

    private func field(id: String?, value: String, type: String) -> [String: Any] {
        var result: [String: Any] = [:]
        if let id = id { result["id"] = id }
        result["value"] = value
        result["type"] = type
        result["pref"] = false
        return result
    }

    /// Canonical, locale-free label for a CNLabeledValue label, so a
    /// read-modify-write roundtrips and both platforms emit identical type
    /// strings; custom labels pass through.
    private func canonicalLabel(_ label: String?) -> String {
        switch label {
        case nil, "": return "other"
        case CNLabelHome: return "home"
        case CNLabelWork: return "work"
        case CNLabelOther: return "other"
        case CNLabelPhoneNumberMobile: return "mobile"
        case CNLabelPhoneNumberMain: return "main"
        case CNLabelPhoneNumberiPhone: return "iphone"
        case CNLabelPhoneNumberPager: return "pager"
        case CNLabelURLAddressHomePage: return "homepage"
        case CNLabelEmailiCloud: return "icloud"
        case CNLabelDateAnniversary: return "anniversary"
        default:
            let label = label ?? ""
            // System labels are "_$!<Name>!$_"-wrapped; localize unknown ones.
            if label.hasPrefix("_$!<") {
                return CNLabeledValue<NSString>.localizedString(forLabel: label).lowercased()
            }
            return label.lowercased()
        }
    }

    // MARK: - Mapping dictionary -> CNMutableContact

    func apply(_ dict: [String: Any], to contact: CNMutableContact) {
        // A field present in the payload replaces the stored value entirely;
        // absent fields are left untouched (matches the Android side).
        if let name = dict["name"] as? [String: Any] {
            contact.givenName = name["givenName"] as? String ?? ""
            contact.familyName = name["familyName"] as? String ?? ""
            contact.middleName = name["middleName"] as? String ?? ""
            contact.namePrefix = name["honorificPrefix"] as? String ?? ""
            contact.nameSuffix = name["honorificSuffix"] as? String ?? ""
        }

        if let nickname = dict["nickname"] as? String {
            contact.nickname = nickname
        }

        // `note` is intentionally not written on iOS: setting it requires the
        // restricted `com.apple.developer.contacts.notes` entitlement, and
        // saving with a note set throws without it. Documented as iOS-unsupported.

        if let phoneNumbers = dict["phoneNumbers"] as? [[String: Any]] {
            contact.phoneNumbers = phoneNumbers.compactMap { entry in
                guard let value = entry["value"] as? String, !value.isEmpty else { return nil }
                return CNLabeledValue(
                    label: labelKey(entry["type"] as? String),
                    value: CNPhoneNumber(stringValue: value)
                )
            }
        }

        if let emails = dict["emails"] as? [[String: Any]] {
            contact.emailAddresses = emails.compactMap { entry in
                guard let value = entry["value"] as? String, !value.isEmpty else { return nil }
                return CNLabeledValue(label: labelKey(entry["type"] as? String), value: value as NSString)
            }
        }

        if let urls = dict["urls"] as? [[String: Any]] {
            contact.urlAddresses = urls.compactMap { entry in
                guard let value = entry["value"] as? String, !value.isEmpty else { return nil }
                return CNLabeledValue(label: labelKey(entry["type"] as? String), value: value as NSString)
            }
        }

        if let addresses = dict["addresses"] as? [[String: Any]] {
            contact.postalAddresses = addresses.compactMap { address in
                let postal = CNMutablePostalAddress()
                postal.street = (address["streetAddress"] as? String) ?? ""
                postal.city = (address["locality"] as? String) ?? ""
                postal.state = (address["region"] as? String) ?? ""
                postal.postalCode = (address["postalCode"] as? String) ?? ""
                postal.country = (address["country"] as? String) ?? ""
                return CNLabeledValue(label: labelKey(address["type"] as? String), value: postal)
            }
        }

        if let ims = dict["ims"] as? [[String: Any]] {
            contact.instantMessageAddresses = ims.compactMap { entry in
                guard let value = entry["value"] as? String, !value.isEmpty else { return nil }
                let service = (entry["type"] as? String) ?? ""
                return CNLabeledValue(
                    label: nil,
                    value: CNInstantMessageAddress(username: value, service: service)
                )
            }
        }

        if let organizations = dict["organizations"] as? [[String: Any]], let org = organizations.first {
            contact.organizationName = (org["name"] as? String) ?? ""
            contact.departmentName = (org["department"] as? String) ?? ""
            contact.jobTitle = (org["title"] as? String) ?? ""
        }

        if let birthdayMs = (dict["birthday"] as? NSNumber)?.doubleValue {
            let date = Date(timeIntervalSince1970: birthdayMs / 1000.0)
            contact.birthday = Calendar.current.dateComponents([.year, .month, .day], from: date)
        }

        if let photos = dict["photos"] as? [[String: Any]], let photo = photos.first,
           let data = photoData(from: photo) {
            contact.imageData = data
        }
    }

    /// Decodes the first photo entry into image bytes: `base64` values are
    /// decoded directly; `url` values are read from local `file://` URLs
    /// (matching the legacy plugin's photo import behavior).
    private func photoData(from photo: [String: Any]) -> Data? {
        guard let value = photo["value"] as? String, !value.isEmpty else { return nil }
        let type = (photo["type"] as? String)?.lowercased() ?? "url"
        if type == "base64" {
            return Data(base64Encoded: value, options: .ignoreUnknownCharacters)
        }
        guard let url = URL(string: value), url.isFileURL else { return nil }
        return try? Data(contentsOf: url)
    }

    /// Maps a free-form label string to a Contacts framework label constant.
    private func labelKey(_ type: String?) -> String {
        switch type?.lowercased() {
        case "home": return CNLabelHome
        case "work": return CNLabelWork
        case "mobile", "cell": return CNLabelPhoneNumberMobile
        case "main": return CNLabelPhoneNumberMain
        case "iphone": return CNLabelPhoneNumberiPhone
        case "pager": return CNLabelPhoneNumberPager
        case "homepage": return CNLabelURLAddressHomePage
        case "other", nil, "": return CNLabelOther
        default: return type ?? CNLabelOther
        }
    }
}
