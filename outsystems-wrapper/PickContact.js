// JS node for the PickContact client action.
// Outputs: Success (Boolean), ErrorMessage (Text), ContactJSON (Text)
function onSuccess(contact) {
    $parameters.ContactJSON = JSON.stringify(contact);
    $parameters.Success = true;
    $parameters.ErrorMessage = "";
    $resolve(); 
}

function onError(err) {
    $parameters.Success = false;
    $parameters.ErrorMessage = "Could not pick contact";
    $resolve();
}

const CapacitorContacts = (window.CapacitorPlugins && window.CapacitorPlugins.Contacts) ||
    (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Contacts);

if (CapacitorContacts) {
    CapacitorContacts.pickContact().then(function (picked) {
        // Legacy wire shape: birthday serialized as an ISO string.
        if (picked.birthday != null) picked.birthday = new Date(picked.birthday);
        onSuccess(picked);
    }, onError);
} else if (!navigator.contacts) { 
    onError(""); 
} else {
    // pick contact from device's contact picker
    navigator.contacts.pickContact(onSuccess, onError);
}
