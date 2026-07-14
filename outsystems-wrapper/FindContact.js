// JS node for the FindContact client action.
// Inputs: SearchParameter (Text), MultipleContacts (Boolean)
// Outputs: Success (Boolean), ErrorMessage (Text), ContactsJSON (Text)
// Capacitor shell (ODC / MABS 12+) uses @capacitor/contacts; the Cordova
// shell (O11 / MABS 11) falls back to the legacy navigator.contacts plugin.

function onSuccess(contacts) {
    $parameters.Success = true;
    $parameters.ErrorMessage = "";
    $parameters.ContactsJSON = JSON.stringify(contacts);
    $resolve();
}

function onError(error) {
    $parameters.Success = false;
    $parameters.ErrorMessage = error && error.message ? error.message : "Could not find contact";
    $resolve();
}

const CapacitorContacts = (window.CapacitorPlugins && window.CapacitorPlugins.Contacts) ||
    (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Contacts);

if (CapacitorContacts) {
    CapacitorContacts.find({
        fields: ["*"],
        filter: $parameters.SearchParameter,
        multiple: $parameters.MultipleContacts
    }).then(function (result) {
        // Legacy wire shape: birthday serialized as an ISO string (a JS
        // Date), so downstream TextToDateTime logic keeps working unchanged.
        onSuccess(result.contacts.map(function (c) {
            if (c.birthday != null) c.birthday = new Date(c.birthday);
            return c;
        }));
    }, onError);
} else {
    let options = new ContactFindOptions();
    options.filter = $parameters.SearchParameter;
    options.multiple = $parameters.MultipleContacts;
    navigator.contacts.find(["*"], onSuccess, onError, options);
}
