import { Contacts } from '@capacitor/contacts';

let lastId = null;

function show(label, data) {
  document.getElementById('out').textContent = `${label}\n${JSON.stringify(data, null, 2)}`;
}

async function run(label, fn) {
  document.getElementById('out').textContent = `${label} …`;
  try {
    const result = await fn();
    show(`✅ ${label}`, result ?? 'OK (void)');
    return result;
  } catch (e) {
    show(`❌ ${label}`, { code: e.code, message: e.message });
    return undefined;
  }
}

window.testFind = async () => {
  const filter = document.getElementById('filterInput').value;
  await run('find', () => Contacts.find({ fields: ['*'], filter, multiple: true }));
};

window.testFindPhones = async () => {
  await run('find hasPhoneNumber', () =>
    Contacts.find({ fields: ['*'], multiple: true, hasPhoneNumber: true, desiredFields: ['name', 'phoneNumbers'] }),
  );
};

window.testSaveNew = async () => {
  const givenName = document.getElementById('givenNameInput').value || 'Ada';
  const saved = await run('save (create)', () =>
    Contacts.save({
      contact: {
        name: { givenName, familyName: 'Lovelace' },
        phoneNumbers: [{ type: 'mobile', value: '+351910000000' }],
        emails: [{ type: 'home', value: 'ada@example.com' }],
        birthday: Date.UTC(1815, 11, 10),
      },
    }),
  );
  if (saved?.id) lastId = saved.id;
};

window.testSaveUpdate = async () => {
  if (!lastId) {
    show('❌ save (update)', 'No saved contact yet; run save() first.');
    return;
  }
  await run('save (update)', () =>
    Contacts.save({
      contact: {
        id: lastId,
        name: { givenName: 'Ada', familyName: 'Lovelace' },
        nickname: 'Countess',
        organizations: [{ type: 'work', name: 'Analytical Engines', title: 'Mathematician' }],
      },
    }),
  );
};

window.testPick = async () => {
  const picked = await run('pickContact', () => Contacts.pickContact());
  if (picked?.id) lastId = picked.id;
};

window.testRemove = async () => {
  if (!lastId) {
    show('❌ remove', 'No contact id captured yet; save or pick a contact first.');
    return;
  }
  await run('remove', () => Contacts.remove({ id: lastId }));
  lastId = null;
};
