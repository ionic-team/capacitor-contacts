export interface ContactsPlugin {
  /**
   * Queries the device contacts database and returns the matching contacts.
   *
   * Requests the READ_CONTACTS (Android) / Contacts (iOS) permission internally
   * the first time it runs; there is no separate permission method.
   *
   * **iOS 18+:** under Limited Access the search runs against (and returns
   * only) the subset of contacts the user shared with the app.
   *
   * @since 1.0.0
   */
  find(options: ContactFindOptions): Promise<ContactFindResult>;

  /**
   * Persists a new contact or updates an existing one (matched by `contact.id`).
   * Resolves with the full saved contact.
   *
   * Requests the READ/WRITE_CONTACTS (Android) / Contacts (iOS) permission
   * internally.
   *
   * **iOS 18+:** works under Limited Access: new contacts are added to the
   * app's accessible set; updating requires the target contact to be in that
   * set (otherwise the call rejects with `OS-PLUG-CONT-0001`).
   *
   * @since 1.0.0
   */
  save(options: ContactSaveOptions): Promise<Contact>;

  /**
   * Removes the contact with the given `id` from the device contacts database.
   * Rejects with `OS-PLUG-CONT-0001` when no contact has that id (on iOS 18+
   * Limited Access, also when the contact is outside the accessible set).
   *
   * Requests the READ/WRITE_CONTACTS (Android) / Contacts (iOS) permission
   * internally.
   *
   * @since 1.0.0
   */
  remove(options: ContactRemoveOptions): Promise<void>;

  /**
   * Launches the native contact picker UI and resolves with the contact the
   * user selects. Rejects with `OS-PLUG-CONT-0006` if the user cancels.
   *
   * On iOS the system picker requires no permission and always shows the full
   * contact list, even under iOS 18+ Limited Access (the picked contact is
   * returned without joining the app's accessible set). On Android the
   * READ_CONTACTS permission is requested internally to read the picked
   * contact's details.
   *
   * @since 1.0.0
   */
  pickContact(): Promise<Contact>;
}

/**
 * The set of contact fields a {@link ContactsPlugin.find} call can search
 * against or request back. Mirrors the legacy Cordova `ContactFieldType`
 * string values exactly.
 *
 * @since 1.0.0
 */
export type ContactFieldType =
  | 'addresses'
  | 'birthday'
  | 'categories'
  | 'country'
  | 'department'
  | 'displayName'
  | 'emails'
  | 'familyName'
  | 'formatted'
  | 'givenName'
  | 'honorificPrefix'
  | 'honorificSuffix'
  | 'id'
  | 'ims'
  | 'locality'
  | 'middleName'
  | 'name'
  | 'nickname'
  | 'note'
  | 'organizations'
  | 'phoneNumbers'
  | 'photos'
  | 'postalCode'
  | 'region'
  | 'streetAddress'
  | 'title'
  | 'urls';

/**
 * Search options accepted by {@link ContactsPlugin.find}.
 *
 * @since 1.0.0
 */
export interface ContactFindOptions {
  /**
   * Fields to search against. Pass `['*']` to match every field. An empty
   * array is invalid and rejects with `OS-PLUG-CONT-0001`. The `id` field
   * matches by exact identifier; all other fields match case-insensitive
   * substrings. `photos` and `categories` are not searchable.
   *
   * @since 1.0.0
   */
  fields: ContactFieldType[];

  /**
   * Search string matched (case-insensitively) against the selected `fields`.
   * An empty/omitted filter returns every contact.
   *
   * @since 1.0.0
   */
  filter?: string;

  /**
   * When `true`, returns every match; when `false` (default), returns at most
   * one contact.
   *
   * @since 1.0.0
   */
  multiple?: boolean;

  /**
   * If set, each returned {@link Contact} only includes these fields (plus the
   * always-present `id`).
   *
   * @since 1.0.0
   */
  desiredFields?: ContactFieldType[];

  /**
   * OutSystems extension: when `true`, only contacts that have at least one
   * phone number are returned. Defaults to `false`.
   *
   * @since 1.0.0
   */
  hasPhoneNumber?: boolean;
}

/**
 * Result of a {@link ContactsPlugin.find} call.
 *
 * @since 1.0.0
 */
export interface ContactFindResult {
  /**
   * The contacts matching the search criteria.
   *
   * @since 1.0.0
   */
  contacts: Contact[];
}

/**
 * Options accepted by {@link ContactsPlugin.save}.
 *
 * @since 1.0.0
 */
export interface ContactSaveOptions {
  /**
   * The contact to create (no `id`) or update (existing `id`).
   *
   * Update semantics: every field present on the contact replaces the stored
   * value entirely (e.g. `name` replaces the whole structured name,
   * `phoneNumbers` replaces all phone numbers); omitted fields are left
   * unchanged.
   *
   * @since 1.0.0
   */
  contact: Contact;
}

/**
 * Options accepted by {@link ContactsPlugin.remove}.
 *
 * @since 1.0.0
 */
export interface ContactRemoveOptions {
  /**
   * The native id of the contact to remove.
   *
   * @since 1.0.0
   */
  id: string;
}

/**
 * A single device contact.
 *
 * @since 1.0.0
 */
export interface Contact {
  /**
   * Globally unique, platform-assigned identifier. Absent for contacts not yet
   * saved to the device.
   *
   * @since 1.0.0
   */
  id?: string;

  /**
   * Android raw-contact id backing this aggregated contact. iOS leaves this
   * unset.
   *
   * @since 1.0.0
   */
  rawId?: string;

  /**
   * Name suitable for display to end users.
   *
   * @since 1.0.0
   */
  displayName?: string;

  /**
   * The structured name components.
   *
   * @since 1.0.0
   */
  name?: ContactName;

  /**
   * A casual name by which to address the contact.
   *
   * @since 1.0.0
   */
  nickname?: string;

  /**
   * The contact's phone numbers.
   *
   * @since 1.0.0
   */
  phoneNumbers?: ContactField[];

  /**
   * The contact's email addresses.
   *
   * @since 1.0.0
   */
  emails?: ContactField[];

  /**
   * The contact's postal addresses.
   *
   * @since 1.0.0
   */
  addresses?: ContactAddress[];

  /**
   * The contact's instant-messaging handles.
   *
   * @since 1.0.0
   */
  ims?: ContactField[];

  /**
   * The contact's organizations.
   *
   * @since 1.0.0
   */
  organizations?: ContactOrganization[];

  /**
   * The contact's birthday as epoch milliseconds.
   *
   * @since 1.0.0
   */
  birthday?: number;

  /**
   * A free-form note about the contact.
   *
   * **iOS:** not supported by default: reading/writing a contact's note
   * requires Apple's restricted `com.apple.developer.contacts.notes`
   * entitlement. Without it the field is omitted on read and ignored on save.
   * Android has no such restriction.
   *
   * @since 1.0.0
   */
  note?: string;

  /**
   * The contact's photos. Reads return `type: 'url'` with the `value`
   * holding a reference to the image, never image bytes: on Android the
   * contact's `content://` photo URI, on iOS the path of a copy written to
   * the app's temporary directory (cleared by the system). On save, the first entry is
   * applied: pass `type: 'base64'` with base64 data, or `type: 'url'` with a
   * local `file://`/`content://` URI to import.
   *
   * @since 1.0.0
   */
  photos?: ContactField[];

  /**
   * User-defined categories associated with the contact. Read-only: populated
   * from the contact's group memberships on Android, never returned on iOS
   * (the Contacts framework has no equivalent), and ignored on save.
   *
   * @since 1.0.0
   */
  categories?: ContactField[];

  /**
   * Web pages associated with the contact.
   *
   * @since 1.0.0
   */
  urls?: ContactField[];
}

/**
 * Structured name of a {@link Contact}.
 *
 * @since 1.0.0
 */
export interface ContactName {
  /**
   * The complete formatted name.
   *
   * @since 1.0.0
   */
  formatted?: string;

  /**
   * Family (last) name.
   *
   * @since 1.0.0
   */
  familyName?: string;

  /**
   * Given (first) name.
   *
   * @since 1.0.0
   */
  givenName?: string;

  /**
   * Middle name.
   *
   * @since 1.0.0
   */
  middleName?: string;

  /**
   * Honorific prefix (e.g. `Mr.`, `Dr.`).
   *
   * @since 1.0.0
   */
  honorificPrefix?: string;

  /**
   * Honorific suffix (e.g. `Esq.`).
   *
   * @since 1.0.0
   */
  honorificSuffix?: string;
}

/**
 * A generic, repeatable contact field (phone number, email, IM, photo, URL,
 * category).
 *
 * @since 1.0.0
 */
export interface ContactField {
  /**
   * The kind of field, e.g. `home`, `work`, `mobile`. For photos, `url` or
   * `base64`.
   *
   * @since 1.0.0
   */
  type?: string;

  /**
   * The field value (phone number, email address, URI, etc.).
   *
   * @since 1.0.0
   */
  value?: string;

  /**
   * `true` if this is the contact's preferred value for the field.
   *
   * @since 1.0.0
   */
  pref?: boolean;

  /**
   * Platform-assigned id of this individual field entry.
   *
   * @since 1.0.0
   */
  id?: string;
}

/**
 * A postal address of a {@link Contact}.
 *
 * @since 1.0.0
 */
export interface ContactAddress {
  /**
   * Platform-assigned id of this address entry.
   *
   * @since 1.0.0
   */
  id?: string;

  /**
   * `true` if this is the contact's preferred address.
   *
   * @since 1.0.0
   */
  pref?: boolean;

  /**
   * The kind of address, e.g. `home`, `work`.
   *
   * @since 1.0.0
   */
  type?: string;

  /**
   * The full address formatted for display.
   *
   * @since 1.0.0
   */
  formatted?: string;

  /**
   * The street address.
   *
   * @since 1.0.0
   */
  streetAddress?: string;

  /**
   * The city or locality.
   *
   * @since 1.0.0
   */
  locality?: string;

  /**
   * The state or region.
   *
   * @since 1.0.0
   */
  region?: string;

  /**
   * The ZIP or postal code.
   *
   * @since 1.0.0
   */
  postalCode?: string;

  /**
   * The country name.
   *
   * @since 1.0.0
   */
  country?: string;
}

/**
 * An organization a {@link Contact} belongs to.
 *
 * @since 1.0.0
 */
export interface ContactOrganization {
  /**
   * Platform-assigned id of this organization entry. Android only; iOS
   * models the organization as flat contact properties without an id.
   *
   * @since 1.0.0
   */
  id?: string;

  /**
   * `true` if this is the contact's preferred organization.
   *
   * @since 1.0.0
   */
  pref?: boolean;

  /**
   * The kind of organization, e.g. `work`.
   *
   * @since 1.0.0
   */
  type?: string;

  /**
   * The organization name.
   *
   * @since 1.0.0
   */
  name?: string;

  /**
   * The department within the organization.
   *
   * @since 1.0.0
   */
  department?: string;

  /**
   * The contact's title at the organization.
   *
   * @since 1.0.0
   */
  title?: string;
}
