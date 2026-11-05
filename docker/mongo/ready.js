const admin = db.getSiblingDB('admin');
const replicaSet = process.env.MONGO_REPLICA_SET || 'vitaloom-rs';

try {
    if (!admin.auth(process.env.MONGO_INITDB_ROOT_USERNAME, process.env.MONGO_INITDB_ROOT_PASSWORD)) {
        quit(1);
    }

    try {
        const status = admin.runCommand({ replSetGetStatus: 1 });
        if (status.ok !== 1) {
            throw status;
        }
    } catch (error) {
        if (error.code !== 94) {
            throw error;
        }

        const result = admin.runCommand({
            replSetInitiate: {
                _id: replicaSet,
                members: [{ _id: 0, host: 'localhost:27017' }]
            }
        });

        if (result.ok !== 1) {
            quit(1);
        }
    }

    const hello = admin.runCommand({ hello: 1 });
    quit(hello.setName === replicaSet && hello.isWritablePrimary === true ? 0 : 1);
} catch (error) {
    print('MongoDB replica set readiness failed: ' + (error.codeName || error.name));
    quit(1);
}
