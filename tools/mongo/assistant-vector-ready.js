const admin = db.getSiblingDB("admin");
const hello = admin.runCommand({hello: 1});

if (hello.ok !== 1 || hello.isWritablePrimary !== true) {
    quit(1);
}

try {
    admin.getCollection("system.version").getSearchIndexes();
    quit(0);
} catch (error) {
    print("Assistant Vector Search readiness failed: " + (error.codeName || error.name));
    quit(1);
}
