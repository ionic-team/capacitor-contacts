// JS node for the AddToContacts client action.
// Inputs: FirstName, LastName, Phone, Email (Text)
// Outputs: Success (Boolean), ErrorMessage (Text)
function onSuccess(contact) {
  $parameters.Success = true;
  $parameters.ErrorMessage = '';
  $resolve();
}

function onError(contactError) {
  $parameters.Success = false;
  $parameters.ErrorMessage = 'Could not save contact';
  $resolve();
}

const CapacitorContacts =
  (window.CapacitorPlugins && window.CapacitorPlugins.Contacts) ||
  (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Contacts);

if (CapacitorContacts) {
  CapacitorContacts.save({
    contact: {
      name: { givenName: $parameters.FirstName, familyName: $parameters.LastName },
      phoneNumbers: [{ type: 'mobile', value: $parameters.Phone, pref: true }],
      emails: [{ type: 'work', value: $parameters.Email, pref: true }],
    },
  }).then(onSuccess, onError);
} else {
  let name = new ContactName();
  name.givenName = $parameters.FirstName;
  name.familyName = $parameters.LastName;

  let phoneNumbers = [];
  phoneNumbers[0] = new ContactField('mobile', $parameters.Phone, true);

  let emails = [];
  emails[0] = new ContactField('work', $parameters.Email, true);

  let contact = navigator.contacts.create();
  contact.name = name;
  contact.phoneNumbers = phoneNumbers;
  contact.emails = emails;

  // save to device
  contact.save(onSuccess, onError);
}
