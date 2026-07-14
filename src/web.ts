import { WebPlugin } from '@capacitor/core';

import type {
  Contact,
  ContactFindOptions,
  ContactFindResult,
  ContactRemoveOptions,
  ContactSaveOptions,
  ContactsPlugin,
} from './definitions';

/**
 * Web implementation. The browser has no standardized, broadly-available
 * contacts database API (the Contact Picker API is limited, gated, and
 * read-only), so every method throws `unimplemented()`. The plugin is
 * native-only on iOS and Android.
 */
export class ContactsWeb extends WebPlugin implements ContactsPlugin {
  async find(_options: ContactFindOptions): Promise<ContactFindResult> {
    throw this.unimplemented('Contacts.find() is not available on web.');
  }

  async save(_options: ContactSaveOptions): Promise<Contact> {
    throw this.unimplemented('Contacts.save() is not available on web.');
  }

  async remove(_options: ContactRemoveOptions): Promise<void> {
    throw this.unimplemented('Contacts.remove() is not available on web.');
  }

  async pickContact(): Promise<Contact> {
    throw this.unimplemented('Contacts.pickContact() is not available on web.');
  }
}
