import { Contacts } from '@capacitor/contacts';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    Contacts.echo({ value: inputValue })
}
