# OutSystems wrapper scripts

Reference source for the JS nodes of the OutSystems Contacts plugin (the
Mobile Library wrapping this plugin). Each script runtime-detects the shell:
the Capacitor plugin (`CapacitorPlugins.Contacts`) on ODC / MABS 12+, falling
back to the legacy `navigator.contacts` Cordova plugin on O11 / MABS 11 —
the legacy branches are the current production node code verbatim. When
these scripts change, copy them into the corresponding JS node in ODC Studio.

| File | JS node (client action) | Notes |
| --- | --- | --- |
| `FindContact.js` | `FindContactJS` (FindContact) | `ContactsJSON` is byte-compatible across both runtimes (birthday serialized as an ISO string); the deserialize/ForEach flow needs no changes. |
| `AddToContacts.js` | `AddToContactsJS` (AddToContacts) | Same FirstName/LastName/Phone/Email arguments; the flow's email-validation step is untouched. |
| `RemoveFromContacts.js` | `RemoveFromContactsJS` (RemoveFromContacts) | Flow edits required: change the client action input from the Contact record to `ContactId` (Text), delete the `JSONSerialize1` node, and point the JS node's argument at `ContactId`. The legacy branch sends the id through a contact stub exactly like the old node did. |
| `PickContact.js` | `PickContact` (PickContact) | No arguments; same `ContactJSON` output on both branches. |
| `CheckContactsPlugin.js` | `IsPluginAvailableJS` (CheckContactsPlugin) | **Must be updated**: the current node checks only `cordova`/`navigator`, so Capacitor builds would report the plugin unavailable and every action would short-circuit at the "Is Contacts Plugin available?" gate. |

`extensibility-configuration.json` carries **both** build sources: MABS picks
`cordova` (the unchanged legacy plugin) or `capacitor` per shell — update the
`capacitor` npm source to the released tag. `GET_ACCOUNTS` must remain in the
Android permissions while the legacy Cordova source is used (its
`AccountManager` save path needs it); the Capacitor plugin does not use it.
Create the `ContactsUsageDescription` extensibility setting in ODC Studio.
