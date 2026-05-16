const admin = require('firebase-admin');
const serviceAccount = require("./mobile-cb29c-firebase-adminsdk-fbsvc-fab75eea2b.json");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

const db = admin.firestore();

module.exports = {admin, db};